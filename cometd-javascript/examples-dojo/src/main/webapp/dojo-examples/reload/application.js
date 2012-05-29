require({
        packages: [{
            name: 'dojox/cometd',
            location: '/dojox/cometd'
        },{
            name: 'org',
            location: '/org'
        }]
    },
    ['dojox/cometd', 'dojo/dom', 'dojo/_base/unload', 'dojox/cometd/reload'],
    function(cometd, dom, unload)
{
    /* handshake listener to report client IDs */
    cometd.addListener("/meta/handshake", function (message)
    {
        if (message.successful)
        {
            dom.byId('previous').innerHTML = org.cometd.COOKIE.get('demoLastCometdID');
            dom.byId('current').innerHTML = message.clientId;
            org.cometd.COOKIE.set('demoLastCometdID', message.clientId, {
                'max-age': 300,
                path : '/',
                expires: new Date(new Date().getTime() + 300 * 1000)
            });
        }
        else
        {
            dom.byId('previous').innerHTML = 'Handshake Failed';
            dom.byId('current').innerHTML = 'Handshake Failed';
        }
    });

    /* connect listener to report advice */
    cometd.addListener("/meta/connect", function (message)
    {
        if (message.advice)
        {
            dom.byId('advice').innerHTML = org.cometd.JSON.toJSON(message.advice);
        }
    });

    /* Initialize CometD */
    var cometURL = new String(document.location).replace(/\/dojo-examples\/.*$/, '') + "/cometd";
    cometd.init({
        url: cometURL,
        logLevel: "debug"
    });

    /* Setup reload extension */
    unload.addOnUnload(cometd, "reload");
});
