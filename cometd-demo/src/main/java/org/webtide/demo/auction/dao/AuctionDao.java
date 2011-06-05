package org.webtide.demo.auction.dao;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.webtide.demo.auction.Bid;

public class AuctionDao
{
    final static ConcurrentMap<Integer, List<Bid>> _bids = new ConcurrentHashMap<Integer, List<Bid>>();

    public List<Bid> getAllBids(Integer itemId)
    {
        return _bids.get(itemId);
    }

    public void saveAuctionBid(Bid bid)
    {
        List<Bid> bids = _bids.get(bid.getItemId());
        if (bids == null)
        {
            bids = new CopyOnWriteArrayList<Bid>();
            List<Bid> tmp = _bids.putIfAbsent(bid.getItemId(),bids);
            bids = tmp == null?bids:tmp;
        }
        bids.add(bid.clone());
    }

    public Bid getHighestBid(Integer itemId)
    {
        List<Bid> bids = _bids.get(itemId);
        if (bids == null)
            return null;

        Bid highest = null;

        for (Bid bid : bids)
        {
            if (highest == null || bid.getAmount() > highest.getAmount())
                highest = bid;
        }

        return highest.clone();
    }

}
