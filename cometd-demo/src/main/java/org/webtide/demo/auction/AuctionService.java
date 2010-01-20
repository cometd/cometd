/**
 *
 */
package org.webtide.demo.auction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletContext;

import org.cometd.Bayeux;
import org.cometd.Channel;
import org.cometd.ChannelBayeuxListener;
import org.cometd.Client;
import org.cometd.SubscriptionListener;
import org.cometd.oort.Oort;
import org.cometd.oort.Seti;
import org.cometd.server.BayeuxService;
import org.webtide.demo.auction.dao.AuctionDao;
import org.webtide.demo.auction.dao.BidderDao;
import org.webtide.demo.auction.dao.CategoryDao;


public class AuctionService extends BayeuxService implements SubscriptionListener, ChannelBayeuxListener
{
    public static final String AUCTION_ROOT="/auction/";
    
    AuctionDao _auctionDao=new AuctionDao();
    BidderDao _bidderDao=new BidderDao();
    CategoryDao _categoryDao=new CategoryDao();
    
    private Oort _oort;
    private Seti _seti;
    private AtomicInteger _bidders=new AtomicInteger(0);
    

    public AuctionService(ServletContext context)
    {
        super((Bayeux)context.getAttribute(Bayeux.ATTRIBUTE), "oortion");

        _oort = (Oort)context.getAttribute(Oort.OORT_ATTRIBUTE);
        if (_oort==null)
            throw new RuntimeException("!"+Oort.OORT_ATTRIBUTE);
        _seti = (Seti)context.getAttribute(Seti.SETI_ATTRIBUTE);
        if (_seti==null)
            throw new RuntimeException("!"+Seti.SETI_ATTRIBUTE);

        _oort.observeChannel(AUCTION_ROOT+"**");

        getBayeux().addListener(this);
        setSeeOwnPublishes(false);
        subscribe(AUCTION_ROOT+"*", "bids");
        subscribe("/service"+AUCTION_ROOT+"bid", "bid");
        subscribe("/service"+AUCTION_ROOT+"bidder", "bidder");
        subscribe("/service"+AUCTION_ROOT+"search", "search");
        subscribe("/service"+AUCTION_ROOT+"category", "category");
        subscribe("/service"+AUCTION_ROOT+"categories", "categories");
    }

    public Bidder bidder(Client source, String channel, String bidder, String messageId)
    {
        Integer id = _bidders.incrementAndGet();
        
        // TODO this is not atomic, but will do for the demo
        String username=bidder.toLowerCase().replace(" ","");
        while(_bidderDao.getBidder(username)!=null)
            username=bidder.toLowerCase().replace(" ","")+"-"+_bidders.incrementAndGet();
        Bidder b=new Bidder();
        b.setName(bidder);
        b.setUsername(username);
        _bidderDao.addBidder(b);
        _seti.associate(b.getUsername(),source);
        return b;   
    }
    
    public List<Category> categories(Client source, String channel, String bidder, String messageId)
    {
        return _categoryDao.getAllCategories();
    }

    public List<Item> search(Client source, String channel, String search, String messageId)
    {  
        return _categoryDao.findItems(search);
    }

    public List<Item> category(Client source, String channel, Number categoryId, String messageId)
    { 
        return _categoryDao.getItemsInCategory(categoryId.intValue());
    }

    public synchronized void bid(Client source, String channel, Map<String,Object> bidMap, String messageId)
    {
        try
        {
            Integer itemId = ((Number)bidMap.get("itemId")).intValue();
            Double amount = Double.parseDouble(bidMap.get("amount").toString());
            String username = (String)bidMap.get("username");
            Bidder bidder = _bidderDao.getBidder(username);

            if (bidder!=null)
            {
                // TODO This is a horrible race because there is no clusterwide DB for
                // atomic determinition of the highest bid.
                // live with it! it's a demo!!!!

                Bid highest = _auctionDao.getHighestBid(itemId);
                if (highest==null || amount>highest.getAmount())
                {
                    Bid bid = new Bid();
                    bid.setItemId(itemId);
                    bid.setAmount(amount);
                    bid.setBidder(bidder);
                    _auctionDao.saveAuctionBid(bid);
                    getBayeux().getChannel(AUCTION_ROOT+"item"+itemId,true).publish(getClient(),bid,messageId);
                }
            }
        }
        catch(NumberFormatException e)
        {
        }
    }
    
    public synchronized void bids(Client source, String channel, Map<String,Object> bidMap, String messageId)
    {
        // TODO Other half of the non atomic bid hack when used in Oort
        Integer itemId = ((Number)bidMap.get("itemId")).intValue();
        Double amount = Double.parseDouble(bidMap.get("amount").toString());
        Map<String,Object> bidderMap = (Map<String,Object>)bidMap.get("bidder");
        String username = (String)bidderMap.get("username");
        Bidder bidder = _bidderDao.getBidder(username);
        if (bidder==null)
        {
            bidder=new Bidder();
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
        if (highest==null || amount>highest.getAmount())
        {
            _auctionDao.saveAuctionBid(bid);
        }
    }

    public void subscribed(Client client, Channel channel)
    {
        if (!client.isLocal()&&channel.getId().startsWith(AUCTION_ROOT+"item"))
        {
            String itemIdS=channel.getId().substring((AUCTION_ROOT+"item").length());
            if (itemIdS.indexOf('/')<0)
            {
                Integer itemId=Integer.decode(itemIdS);
                Bid highest = _auctionDao.getHighestBid(itemId);
                if (highest!=null)
                    client.deliver(getClient(),channel.getId(),highest,null);
            }
        }
    }

    public void unsubscribed(Client client, Channel channel)
    {
    }

    public void channelAdded(Channel channel)
    {   
        if (channel.getId().startsWith(AUCTION_ROOT+"item"))
            channel.addListener(this);
    }

    public void channelRemoved(Channel channel)
    {
    }

    
    
}

