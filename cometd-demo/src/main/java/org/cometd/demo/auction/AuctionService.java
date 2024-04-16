/*
 * Copyright (c) 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.demo.auction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.cometd.annotation.Listener;
import org.cometd.annotation.Service;
import org.cometd.annotation.Session;
import org.cometd.annotation.server.Configure;
import org.cometd.annotation.server.RemoteCall;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.demo.auction.dao.CategoryDao;
import org.cometd.oort.Oort;
import org.cometd.oort.OortLongMap;
import org.cometd.oort.OortMap;
import org.cometd.oort.OortObject;
import org.cometd.oort.OortObjectFactories;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The service that perform bid actions.</p>
 * <p>The bids are stored in a {@link OortLongMap} using the {@link Item#id() item id}
 * as key, and the {@link Bid} as value.</p>
 */
@Service
public class AuctionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionService.class);

    // This lock can be striped for each item id for less contention.
    private final AutoLock lock = new AutoLock();
    private final CategoryDao _categoryDao = new CategoryDao();
    private OortLongMap<Bid> _bids;
    @Inject
    private BayeuxServer _bayeux;
    @Inject
    private Oort _oort;
    @Session
    private ServerSession _session;

    @PostConstruct
    public void construct() throws Exception {
        String channelName = "/auction/bid";
        _bayeux.createChannelIfAbsent(channelName, channel -> channel.setPersistent(true));
        _bids = new OortLongMap<>(_oort, "bids", OortObjectFactories.forConcurrentMap());
        _bids.addEntryListener(new BidListener());
        _bids.start();
    }

    @Configure({"/service/auction/**", "/auction/**"})
    public void setupAuctionChannels(ConfigurableServerChannel channel) {
        channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
    }

    @Listener("/meta/handshake")
    public void login(ServerSession session, ServerMessage message) {
        if (session.isLocalSession()) {
            return;
        }
        String user = (String)message.get("user");
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("logging in user: {}", user);
        }
        if (user == null) {
            ServerMessage.Mutable reply = message.getAssociated();
            reply.setSuccessful(false);
            reply.put(Message.ERROR_FIELD, "invalid_user");
        } else {
            session.setAttribute("user", user);
        }
    }

    @RemoteCall("auction/categories")
    public void getCategories(RemoteCall.Caller caller, Object data) {
        List<Category> categories = _categoryDao.getAllCategories();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("retrieved auction categories {}", categories);
        }
        caller.result(categories);
    }

    @RemoteCall("auction/category")
    public void getItemsByCategory(RemoteCall.Caller caller, Object data) {
        int categoryId = ((Number)data).intValue();
        List<Item> items = _categoryDao.getItemsByCategory(categoryId);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("retrieved auction items for category {} {}", categoryId, items);
        }
        caller.result(items);
    }

    @RemoteCall("auction/search")
    public void searchItems(RemoteCall.Caller caller, Object data) {
        List<Item> items = _categoryDao.searchItems((String)data);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("found auction items {}", items);
        }
        caller.result(items);
    }

    @RemoteCall("auction/bid/current")
    public void currentBid(RemoteCall.Caller caller, Object data) {
        Bid current;
        long itemId = ((Number)data).intValue();
        try (AutoLock ignored = lock.lock()) {
            current = highestBid(itemId);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("current bid {}", current);
        }
        caller.result(current);
    }

    private Bid highestBid(long itemId) {
        assert lock.isHeldByCurrentThread();
        // Lock to handle concurrency among bidders that
        // write the bids and bidders that read the bids.
        return _bids.merge(infos -> {
            Bid highest = null;
            for (OortObject.Info<ConcurrentMap<Long, Bid>> info : infos) {
                ConcurrentMap<Long, Bid> bids = info.getObject();
                Bid bid = bids.get(itemId);
                if (bid == null) {
                    continue;
                }
                if (highest == null || bid.compareTo(highest) > 0) {
                    highest = bid;
                }
            }
            return highest;
        });
    }

    @Listener("/service/auction/bid")
    public void bid(ServerSession session, ServerMessage message) {
        Map<String, Object> data = message.getDataAsMap();
        int id = ((Number)data.get("id")).intValue();
        double amount = ((Number)data.get("amount")).doubleValue();
        String bidder = (String)session.getAttribute("user");
        Bid bid = new Bid(id, amount, bidder);

        // Handle concurrency among clients of the same node.
        long key = id;
        try (AutoLock ignored = lock.lock()) {
            Bid current = _bids.get(key);
            if (current == null || current.compareTo(bid) < 0) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("new bid {}", bid);
                }
                // Update the distributed map of bids.
                _bids.putAndShare(key, bid, null);
            } else {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("stale bid {}, current bid {}", bid, current);
                }
            }
        }
    }

    private class BidListener implements OortMap.EntryListener<Long, Bid> {
        @Override
        public void onPut(OortObject.Info<ConcurrentMap<Long, Bid>> info, OortMap.Entry<Long, Bid> entry) {
            // Calculating highest bid and sending it out must be atomic,
            // otherwise a smaller bid sent to clients can overtake a larger bid.
            try (AutoLock ignored = lock.lock()) {
                Bid bid = highestBid(entry.getKey());
                // Broadcast the bid to the local clients.
                // This channel is not clustered.
                _bayeux.getChannel("/auction/bid").publish(_session, bid, Promise.noop());
            }
        }
    }
}
