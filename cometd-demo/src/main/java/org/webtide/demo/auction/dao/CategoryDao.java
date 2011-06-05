package org.webtide.demo.auction.dao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.webtide.demo.auction.Category;
import org.webtide.demo.auction.Item;

public class CategoryDao
{
    final static ConcurrentMap<Integer, Item> _items = new ConcurrentHashMap<Integer, Item>();
    final static ConcurrentMap<Integer, Category> _categories = new ConcurrentHashMap<Integer, Category>();

    public List<Item> getItemsInCategory(Integer categoryId)
    {
        List<Item> items = new ArrayList<Item>();
        for (Item item : _items.values())
        {
            if (item.getCategory().getId().equals(categoryId))
                items.add(item);
        }
        return items;
    }

    public void addItem(Item item)
    {
        Category category = item.getCategory();
        _categories.putIfAbsent(category.getId(),category.clone());
        _items.putIfAbsent(item.getId(),item.clone());
    }

    public Category getCategory(int categoryId)
    {
        return _categories.get(categoryId);
    }

    public List<Category> getAllCategories()
    {
        List<Category> all = new ArrayList<Category>(_categories.values());
        Collections.sort(all);
        return all;
    }

    public List<Item> findItems(String expression)
    {
        List<Item> items = new ArrayList<Item>();

        String[] words = expression.toLowerCase().split("[ ,]");

        for (Item item : _items.values())
        {
            for (String word: words)
            {
                if (item.getDescription().toLowerCase().contains(word)  ||
                    item.getItemName().toLowerCase().contains(word))
                    items.add(item);
            }
        }
        return items;
    }

    public Item getItem(int itemId)
    {
        return _items.get(itemId);
    }

    public Collection<Item> getAllItems()
    {
        return _items.values();
    }

}
