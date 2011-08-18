org.cometd.Utils = {};

org.cometd.Utils.isString = function(value)
{
    if (value === undefined || value === null)
    {
        return false;
    }
    return typeof value === 'string' ||  value instanceof String;
};

org.cometd.Utils.isArray = function(value)
{
    if (value === undefined || value === null)
    {
        return false;
    }
    return value instanceof Array;
};

/**
 * Returns whether the given element is contained into the given array.
 * @param element the element to check presence for
 * @param array the array to check for the element presence
 * @return the index of the element, if present, or a negative index if the element is not present
 */
org.cometd.Utils.inArray = function(element, array)
{
    for (var i = 0; i < array.length; ++i)
    {
        if (element === array[i])
        {
            return i;
        }
    }
    return -1;
};

org.cometd.Utils.setTimeout = function(cometd, funktion, delay)
{
    return window.setTimeout(function()
    {
        try
        {
            funktion();
        }
        catch (x)
        {
            cometd._debug('Exception invoking timed function', funktion, x);
        }
    }, delay);
};

org.cometd.Utils.clearTimeout = function(timeoutHandle)
{
    window.clearTimeout(timeoutHandle);
};
