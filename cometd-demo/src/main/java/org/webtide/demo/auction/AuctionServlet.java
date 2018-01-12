/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.webtide.demo.auction;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.webtide.demo.auction.dao.CategoryDao;

public class AuctionServlet extends HttpServlet {
    AuctionService _auction;
    AuctionChatService _chat;

    @Override
    public void init() throws ServletException {
        CategoryDao dao = new CategoryDao();

        Category arts = new Category(1, "Arts and Crafts", "Original artworks");
        Category gems = new Category(2, "Jewelry, Gems and Watches", "Jewelry ,gems and different brands of watches");
        Category computers = new Category(3, "Computers", "Brand new and latest models");
        Category antiques = new Category(4, "Antiques and Collectibles", "Rare collectible items");
        Category books = new Category(5, "Books, Movies and Music", "Rare to find items");
        Category cloths = new Category(6, "Clothing and Accessories", "Brand new signature items");
        Category coins = new Category(7, "Coins and Stamps", "Rare coin and stamp collections");

        dao.addItem(new Item(1, "Mona Lisa", arts, "Original painting by Leonardo da Vinci", 2000000D));
        dao.addItem(new Item(2, "Gold Necklace", gems, "24k gold with diamond pendant", 400.5D));
        dao.addItem(new Item(3, "Sony Vaio", computers, "Intel Centrino Duo w/ 1GB RAM. 15.4 inch display", 2000D));
        dao.addItem(new Item(4, "Antique Dining Table", antiques, "Antique dining table from the 18th century", 15000D));
        dao.addItem(new Item(5, "Oil on canvas", arts, "Abstract oil painting on canvas", 1000D));
        dao.addItem(new Item(6, "Dick Tracy Movie Storybook", books, "Dick Tracy storybook by Justine Korman", 150D));
        dao.addItem(new Item(7, "1001 Magic Tricks", antiques, "A collection of different street magic tricks", 100D));
        dao.addItem(new Item(8, "Authentic Leather Jacket", cloths, "Authentic leather jacket", 80D));
        dao.addItem(new Item(9, "Vintage 501 Jeans", cloths, "Vintage 501 jeans", 200D));
        dao.addItem(new Item(10, "Huge Collection of coins", coins, "Different coins from all over the world", 2000D));
        dao.addItem(new Item(11, "19th Century Unused Stamps", coins, "19th Century Unused Stamps", 2000D));
        dao.addItem(new Item(12, "Apple Macbook Pro", computers, "Apple MacBook Pro 2.0GHz Intel Core Duo", 2500D));
        dao.addItem(new Item(13, "ProTrek Titanium Watch", gems, "ProTrek Titanium Triple Sensor Watch", 150D));

        _auction = new AuctionService(getServletContext());
        _chat = new AuctionChatService(getServletContext());
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
    }
}
