package org.webtide.demo.auction;

import java.util.Map;

import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.ajax.JSON.Output;

public class Bid implements Cloneable, JSON.Convertible
{
    private Integer itemId;
    private Double amount;
    private Bidder bidder;

    public Double getAmount()
    {
        return amount;
    }

    public String getFormattedAmount()
    {
        if (amount == null)
            return "";

        return Utils.formatCurrency(getAmount().doubleValue());
    }

    public void setAmount(Double aAmount)
    {
        amount = aAmount;
    }

    public Integer getItemId()
    {
        return this.itemId;
    }

    public void setItemId(Integer itemId)
    {
        this.itemId = itemId;
    }

    public Bidder getBidder()
    {
        return bidder;
    }

    public void setBidder(Bidder aBidder)
    {
        bidder = aBidder;
    }

    public Bid clone()
    {
        try
        {
            return (Bid)super.clone();
        }
        catch (CloneNotSupportedException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void fromJSON(Map object)
    {
    }

    public void toJSON(Output out)
    {
        out.add("itemId",itemId);
        out.add("amount",amount);
        out.add("bidder",bidder);
    }

    public String toString()
    {
        return JSON.toString(this);
    }
}
