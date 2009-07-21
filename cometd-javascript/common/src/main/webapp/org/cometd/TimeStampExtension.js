/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date$
 */

if (typeof dojo!="undefined")
{
    dojo.provide("org.cometd.TimeStampExtension");
}

/**
 * The timestamp extension adds the optional timestamp field to all outgoing messages.
 */

org.cometd.TimeStampExtension = function()
{
    this.outgoing = function(message)
    {
        message.timestamp = new Date().toUTCString();
        return message;
    };
};
