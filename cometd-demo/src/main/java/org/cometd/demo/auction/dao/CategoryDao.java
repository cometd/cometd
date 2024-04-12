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

package org.cometd.demo.auction.dao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.cometd.demo.auction.Category;
import org.cometd.demo.auction.Item;

public class CategoryDao {
    private final ConcurrentMap<Integer, Item> _items = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Category> _categories = new ConcurrentHashMap<>();

    public CategoryDao() {
        Category arts = new Category(1, "Arts and Crafts", "Original artworks");
        Category gems = new Category(2, "Jewelry, Gems and Watches", "Jewelry, gems and different brands of watches");
        Category computers = new Category(3, "Computers", "Brand new and latest computer models");
        Category antiques = new Category(4, "Antiques and Collectibles", "Rare collectible items");
        Category books = new Category(5, "Books, Movies and Music", "Rare to find books, movies and music");
        Category cloths = new Category(6, "Clothing and Accessories", "Brand new signature items");
        Category coins = new Category(7, "Coins and Stamps", "Rare coin and stamp collections");

        addItem(new Item(1, "Mona Lisa", arts, "Original painting by Leonardo da Vinci", 2000000D));
        addItem(new Item(2, "Gold Necklace", gems, "24k gold with diamond pendant", 400.5D));
        addItem(new Item(3, "Sony Vaio", computers, "Intel Centrino Duo w/ 1GB RAM. 15.4 inch display", 2000D));
        addItem(new Item(4, "Antique Dining Table", antiques, "Antique dining table from the 18th century", 15000D));
        addItem(new Item(5, "Oil on canvas", arts, "Abstract oil painting on canvas", 1000D));
        addItem(new Item(6, "Dick Tracy Movie Storybook", books, "Dick Tracy storybook by Justine Korman", 150D));
        addItem(new Item(7, "1001 Magic Tricks", antiques, "A collection of different street magic tricks", 100D));
        addItem(new Item(8, "Authentic Leather Jacket", cloths, "Authentic leather jacket", 80D));
        addItem(new Item(9, "Vintage 501 Jeans", cloths, "Vintage 501 jeans", 200D));
        addItem(new Item(10, "Huge Collection of coins", coins, "Different coins from all over the world", 2000D));
        addItem(new Item(11, "19th Century Unused Stamps", coins, "19th century unused stamps", 2000D));
        addItem(new Item(12, "Apple Macbook Pro", computers, "Apple MacBook Pro 2.0GHz Intel Core Duo", 2500D));
        addItem(new Item(13, "ProTrek Titanium Watch", gems, "ProTrek titanium triple sensor watch", 150D));
    }

    private void addItem(Item item) {
        Category category = item.category();
        _categories.putIfAbsent(category.id(), category);
        _items.putIfAbsent(item.id(), item);
    }

    public List<Category> getAllCategories() {
        List<Category> all = new ArrayList<>(_categories.values());
        Collections.sort(all);
        return all;
    }

    public List<Item> getItemsByCategory(int categoryId) {
        List<Item> items = new ArrayList<>();
        for (Item item : _items.values()) {
            if (item.category().id() == categoryId) {
                items.add(item);
            }
        }
        return items;
    }

    public List<Item> searchItems(String expression) {
        List<Item> items = new ArrayList<>();
        String[] words = expression.toLowerCase().split("[ ,]");
        for (Item item : _items.values()) {
            for (String word : words) {
                if (item.description().toLowerCase().contains(word) ||
                        item.name().toLowerCase().contains(word)) {
                    items.add(item);
                }
            }
        }
        return items;
    }
}
