/*
 * Copyright (c) 2008-2017 the original author or authors.
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
/**
 *
 */

package org.webtide.demo.auction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletContext;

import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.bayeux.server.ServerChannel;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;
import org.cometd.oort.SetiServlet;
import org.cometd.server.AbstractService;
import org.cometd.server.authorizer.GrantAuthorizer;
import org.webtide.demo.auction.dao.AuctionDao;
import org.webtide.demo.auction.dao.BidderDao;
import org.webtide.demo.auction.dao.CategoryDao;

public class AuctionService extends AbstractService implements ClientSessionChannel.MessageListener, BayeuxServer.ChannelListener, BayeuxServer.SubscriptionListener {
    public static final String AUCTION_ROOT = "/auction/";

    private final AuctionDao _auctionDao = new AuctionDao();
    private final BidderDao _bidderDao = new BidderDao();
    private final CategoryDao _categoryDao = new CategoryDao();
    private final AtomicInteger _bidders = new AtomicInteger(0);
    private Oort _oort;
    private Seti _seti;

    public AuctionService(ServletContext context) {
        super((BayeuxServer)context.getAttribute(BayeuxServer.ATTRIBUTE), "oortion");

        _oort = (Oort)context.getAttribute(Oort.OORT_ATTRIBUTE);
        if (_oort == null) {
            throw new RuntimeException("Missing " + Oort.OORT_ATTRIBUTE + " from " + ServletContext.class.getSimpleName() + "; " +
                    "is an Oort servlet declared in web.xml ?");
        }
        _seti = (Seti)context.getAttribute(Seti.SETI_ATTRIBUTE);
        if (_seti == null) {
            throw new RuntimeException("Missing " + Seti.SETI_ATTRIBUTE + " from " + ServletContext.class.getSimpleName() + "; " +
                    "is " + SetiServlet.class.getSimpleName() + " declared in web.xml ?");
        }

        _oort.observeChannel(AUCTION_ROOT + "**");

        getBayeux().addListener(this);
        setSeeOwnPublishes(false);

        getBayeux().createChannelIfAbsent("/service" + AUCTION_ROOT + "*", new ConfigurableServerChannel.Initializer() {
            @Override
            public void configureChannel(ConfigurableServerChannel channel) {
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
            }
        });
        getBayeux().createChannelIfAbsent(AUCTION_ROOT + "*", new ConfigurableServerChannel.Initializer() {
            @Override
            public void configureChannel(ConfigurableServerChannel channel) {
                channel.addAuthorizer(GrantAuthorizer.GRANT_ALL);
            }
        });

        addService(AUCTION_ROOT + "*", "bids");
        addService("/service" + AUCTION_ROOT + "bid", "bid");
        addService("/service" + AUCTION_ROOT + "bidder", "bidder");
        addService("/service" + AUCTION_ROOT + "search", "search");
        addService("/service" + AUCTION_ROOT + "category", "category");
        addService("/service" + AUCTION_ROOT + "categories", "categories");
    }

    public synchronized void bids(ServerSession source, ServerMessage message) {
        // TODO Other half of the non atomic bid hack when used in Oort
        Map<String, Object> bidMap = message.getDataAsMap();
        Integer itemId = ((Number)bidMap.get("itemId")).intValue();
        Double amount = Double.parseDouble(bidMap.get("amount").toString());
        Map<String, Object> bidderMap = (Map<String, Object>)bidMap.get("bidder");
        String username = (String)bidderMap.get("username");
        Bidder bidder = _bidderDao.getBidder(username);
        if (bidder == null) {
            bidder = new Bidder();
            bidder.setUsername(username);
            bidder.setName((String)bidderMap.get("name"));
            _bidderDao.addBidder(bidder);
            bidder = _bidderDao.getBidder(username);
        }

        Bid bid = new Bid();
        bid.setItemId(itemId);
        bid.setAmount(amount);
        bid.setBidder(bidder);

        Bid highest = _auctionDao.getHighestBid(itemId);
        if (highest == null || amount > highest.getAmount()) {
            _auctionDao.saveAuctionBid(bid);
        }
    }

    public synchronized void bid(ServerSession source, ServerMessage message) {
        try {
            Map<String, Object> bidMap = message.getDataAsMap();
            Integer itemId = ((Number)bidMap.get("itemId")).intValue();
            Double amount = Double.parseDouble(bidMap.get("amount").toString());
            String username = (String)bidMap.get("username");
            Bidder bidder = _bidderDao.getBidder(username);

            if (bidder != null) {
                // TODO This is a horrible race because there is no clusterwide DB for
                // atomic determination of the highest bid.
                // live with it! it's a demo!!!!

                Bid highest = _auctionDao.getHighestBid(itemId);
                if (highest == null || amount > highest.getAmount()) {
                    Bid bid = new Bid();
                    bid.setItemId(itemId);
                    bid.setAmount(amount);
                    bid.setBidder(bidder);
                    _auctionDao.saveAuctionBid(bid);
                    getBayeux().getChannel(AUCTION_ROOT + "item" + itemId).publish(getServerSession(), bid);
                }
            }
        } catch (NumberFormatException e) {
        }
    }

    public Bidder bidder(ServerSession source, ServerMessage message) {
        String bidder = (String)message.getData();
        Integer id = _bidders.incrementAndGet();

        // TODO this is not atomic, but will do for the demo
        String username = bidder.toLowerCase().replace(" ", "");
        while (_bidderDao.getBidder(username) != null) {
            username = bidder.toLowerCase().replace(" ", "") + "-" + _bidders.incrementAndGet();
        }
        Bidder b = new Bidder();
        b.setName(bidder);
        b.setUsername(username);
        _bidderDao.addBidder(b);
        _seti.associate(b.getUsername(), source);
        return b;
    }

    public List<Item> search(ServerSession source, ServerMessage message) {
        return _categoryDao.findItems((String)message.getData());
    }

    public List<Item> category(ServerSession source, ServerMessage message) {
        Number categoryId = (Number)message.getData();
        return _categoryDao.getItemsInCategory(categoryId.intValue());
    }

    public List<Category> categories(ServerSession source, ServerMessage message) {
        return _categoryDao.getAllCategories();
    }

    @Override
    public void subscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
        if (!session.isLocalSession() && channel.getId().startsWith(AUCTION_ROOT + "item")) {
            String itemIdS = channel.getId().substring((AUCTION_ROOT + "item").length());
            if (itemIdS.indexOf('/') < 0) {
                Integer itemId = Integer.decode(itemIdS);
                Bid highest = _auctionDao.getHighestBid(itemId);
                if (highest != null) {
                    session.deliver(getServerSession(), channel.getId(), highest);
                }
            }
        }
    }

    @Override
    public void unsubscribed(ServerSession session, ServerChannel channel, ServerMessage message) {
    }

    @Override
    public void channelAdded(ServerChannel channel) {
        if (channel.getId().startsWith(AUCTION_ROOT + "item")) {
            getLocalSession().getChannel(channel.getId()).subscribe(this);
        }
    }

    @Override
    public void channelRemoved(String channelId) {
    }

    @Override
    public void onMessage(ClientSessionChannel channel, Message message) {
    }

    @Override
    public void configureChannel(ConfigurableServerChannel channel) {
    }
}
