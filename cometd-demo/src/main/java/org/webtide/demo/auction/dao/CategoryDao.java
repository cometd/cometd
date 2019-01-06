/*
 * Copyright (c) 2008-2019 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.webtide.demo.auction.Category;
import org.webtide.demo.auction.Item;

public class CategoryDao {
    final static ConcurrentMap<Integer, Item> _items = new ConcurrentHashMap<Integer, Item>();
    final static ConcurrentMap<Integer, Category> _categories = new ConcurrentHashMap<Integer, Category>();

    public List<Item> getItemsInCategory(Integer categoryId) {
        List<Item> items = new ArrayList<Item>();
        for (Item item : _items.values()) {
            if (item.getCategory().getId().equals(categoryId)) {
                items.add(item);
            }
        }
        return items;
    }

    public void addItem(Item item) {
        Category category = item.getCategory();
        _categories.putIfAbsent(category.getId(), category.clone());
        _items.putIfAbsent(item.getId(), item.clone());
    }

    public Category getCategory(int categoryId) {
        return _categories.get(categoryId);
    }

    public List<Category> getAllCategories() {
        List<Category> all = new ArrayList<Category>(_categories.values());
        Collections.sort(all);
        return all;
    }

    public List<Item> findItems(String expression) {
        List<Item> items = new ArrayList<Item>();

        String[] words = expression.toLowerCase().split("[ ,]");

        for (Item item : _items.values()) {
            for (String word : words) {
                if (item.getDescription().toLowerCase().contains(word) ||
                        item.getItemName().toLowerCase().contains(word)) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    public Item getItem(int itemId) {
        return _items.get(itemId);
    }

    public Collection<Item> getAllItems() {
        return _items.values();
    }
}
