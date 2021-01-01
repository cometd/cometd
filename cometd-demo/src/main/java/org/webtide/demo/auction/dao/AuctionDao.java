/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.webtide.demo.auction.dao;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.webtide.demo.auction.Bid;

public class AuctionDao {
    final static ConcurrentMap<Integer, List<Bid>> _bids = new ConcurrentHashMap<Integer, List<Bid>>();

    public List<Bid> getAllBids(Integer itemId) {
        return _bids.get(itemId);
    }

    public void saveAuctionBid(Bid bid) {
        List<Bid> bids = _bids.get(bid.getItemId());
        if (bids == null) {
            bids = new CopyOnWriteArrayList<Bid>();
            List<Bid> tmp = _bids.putIfAbsent(bid.getItemId(), bids);
            bids = tmp == null ? bids : tmp;
        }
        bids.add(bid.clone());
    }

    public Bid getHighestBid(Integer itemId) {
        List<Bid> bids = _bids.get(itemId);
        if (bids == null) {
            return null;
        }

        Bid highest = null;

        for (Bid bid : bids) {
            if (highest == null || bid.getAmount() > highest.getAmount()) {
                highest = bid;
            }
        }

        return highest.clone();
    }
}
