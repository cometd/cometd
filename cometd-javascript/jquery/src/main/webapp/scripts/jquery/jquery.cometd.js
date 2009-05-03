(function($)
{
    // Remap cometd JSON functions to jquery JSON functions
    org.cometd.JSON.toJSON = $.toJSON;

    // Remap cometd AJAX functions to jquery AJAX functions
    org.cometd.AJAX.send = function(packet)
    {
        // TODO
    };

})(jQuery);