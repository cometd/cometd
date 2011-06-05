package org.webtide.demo.auction;

import java.util.Map;

import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.ajax.JSON.Output;

public class Category implements Cloneable, Comparable<Category>, JSON.Convertible
{

    private Integer id;
    private String categoryName;
    private String description;

    public Category()
    {
    };

    public Category(Integer id, String categoryName, String description)
    {

        this.id = id;
        this.categoryName = categoryName;
        this.description = description;
    }

    public String getCategoryName()
    {
        return categoryName;
    }

    public void setCategoryName(String aCategoryName)
    {
        categoryName = aCategoryName;
    }

    public String getDescription()
    {
        return description;
    }

    public void setDescription(String aDescription)
    {
        description = aDescription;
    }

    public Integer getId()
    {
        return id;
    }

    public void setId(Integer aId)
    {
        id = aId;
    }

    public int hashCode()
    {
        return id.hashCode();
    }

    public boolean equals(Object obj)
    {

        if (obj == this)
            return true;
        if (obj == null)
            return false;
        if (!(obj instanceof Category))
            return false;
        return ((Category)obj).getId().equals(id);
    }

    public Category clone()
    {
        try
        {
            return (Category)super.clone();
        }
        catch (CloneNotSupportedException e)
        {
            throw new RuntimeException(e);
        }
    }

    public int compareTo(Category o)
    {
        return categoryName.compareTo(o.categoryName);
    }

    public void fromJSON(Map object)
    {
    }

    public void toJSON(Output out)
    {
        out.add("categoryId",id);
        out.add("categoryName",categoryName);
        out.add("description",description);
    }

    public String toString()
    {
        return JSON.toString(this);
    }
}
