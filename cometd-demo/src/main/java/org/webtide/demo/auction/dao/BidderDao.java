package org.webtide.demo.auction.dao;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.webtide.demo.auction.Bidder;

public class BidderDao
{
    final static ConcurrentMap<String, Bidder> _bidders = new ConcurrentHashMap<String, Bidder>();

    public Bidder getBidder(String username)
    {
        return _bidders.get(username);
    }

    public boolean addBidder (Bidder bidder)
    {
        return _bidders.putIfAbsent(bidder.getUsername(),bidder.clone())==null;
    }

    public void saveBidder (Bidder bidder)
    {
        _bidders.put(bidder.getUsername(),bidder.clone());
    }


}
