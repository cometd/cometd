package org.webtide.demo.auction;

import java.text.DecimalFormat;
import java.text.NumberFormat;

public class Utils
{

    private static final NumberFormat cashAmount = new DecimalFormat("$#,##0.00");

    public static String formatCurrency(double amount)
    {
        return cashAmount.format(amount);
    }

}
