/**
 * Dual licensed under the Apache License 2.0 and the MIT license.
 * $Revision$ $Date$
 */
(function($)
{
    // Remap cometd COOKIE functions to jquery cookie functions
    // Avoid to set to undefined if the jquery cookie plugin is not present
    if ($.cookie)
    {
        org.cometd.COOKIE.set = $.cookie;
        org.cometd.COOKIE.get = $.cookie;
    }

    $.cometd.registerExtension('reload', new org.cometd.ReloadExtension());
})(jQuery);