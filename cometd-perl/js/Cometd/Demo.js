

Template.templates.Foo = '[# for ( var i = 0; i < data.length; i++ ) { #]<div style="margin: 0px; font-family: Courier New; font-size: 12px ">[#= data[ i ].toJSON().replace( /",/g, \'", \' ) #]</div>[# } #]';
Template.templates.Atom = '<div><b>[#|h site #]</b> - (<a href="[#= link #]" target="_blank">link</a>) [#|h title #]</div>';



App.singletonConstructor =
Cometd.Demo = new Class( App, {
    subscriber: true,

    initObject: function() {
        Cometd.Demo.superClass.initObject.apply( this, arguments );

        this.client = new Cometd.Client( "/cometd" );
        this.content = $( "content" );

        this.idCookie = new Cookie(
            "client-id",
            null,
            document.domain,
            '/',
            ( new Date( ( new Date() ).getTime() + 1000 * 60 * 60 * 24 * 365 ) ),
            false
        );

        this.authCookie = new Cookie(
            "auth-token",
            null,
            document.domain,
            '/',
            ( new Date( ( new Date() ).getTime() + 1000 * 60 * 60 * 24 * 365 ) ),
            false
        );

        var clid = this.idCookie.fetch();
        if ( clid && defined( clid.value ) ) {
            this.client.clientId = clid.value;
            log("found client id: "+clid.value);
        }

        var at = this.authCookie.fetch();
        if ( at && defined( at.value ) ) {
            this.client.authToken = at.value;
            log("found auth token: "+at.value);
        } else {
            log("no authToken, removing clientId");
            this.client.clientId = null;
        }
    },


    initComponents: function() {
        Cometd.Demo.superClass.initComponents.apply( this, arguments );

        //this.chat = new Cometd.Chat( "chat-area" );
        //this.addComponent( this.chat );
    },


    exec: function() {

        
        /* use the event system to start it off */
        this.deliverEvents( [
            {
                channel: "/demo/start"
            }
        ] );
    },


    deliver: function( res, obj ) {
        if ( res.error ) {
            log.error( res.error );
            this["/meta/reconnect"]();
        }
        
        if ( res.result ) {
            log.debug( 'delivering: '+res.result.toJSON() );
            this.deliverEvents( res.result );
        }
    },


    /* not a cometd meta */
    "/demo/start": function( ev ) {
        this.req = this.client.handshake({
            heap: {
                callback: this.getIndirectMethod( "deliver" )
            }
        });
    },
    

    "/meta/handshake": function( ev ) {
        /*
        var div = document.createElement( "div" );
        div.innerHTML = Template.process( "Foo", { data: [ ev ], div: div } );
        this.content.insertBefore( div, this.content.firstChild );
        */
        //this.content.appendChild( div );
        //this.content.scrollTop = this.content.scrollHeight;
        
        if ( defined( this.client.clientId ) )
            this.idCookie.bake( this.client.clientId );
        
        if ( defined( this.client.authToken ) )
            this.authCookie.bake( this.client.authToken );

        if ( this.req )
            this.req.stop();

        this.req = this.client.connect({
            heap: {
                callback: this.getIndirectMethod( "deliver" )
            }
        });
    },
            

    "/meta/connect": function() {
        this.content.removeChild( this.content.lastChild );
        var div = document.createElement( "div" );
        div.innerHTML = '<b>Connected!</b>';
        this.content.insertBefore( div, this.content.firstChild );
        
        this[ "/meta/reconnect" ].apply( this, arguments );
    },


    "/meta/reconnect": function( ev ) {
        var delay = 500;
        if ( ev ) {
            /*
            var div = document.createElement( "div" );
            div.innerHTML = Template.process( "Foo", { data: [ ev ], div: div } );
            this.content.insertBefore( div, this.content.firstChild );
              this.content.appendChild( div );
              this.content.scrollTop = this.content.scrollHeight;
            */
            if ( ev.error )
                delay = 15000;
        }
        
        if ( this.req )
            this.req.stop();
        
        this.req = this.client.reconnect({
            heap: {
                callback: this.getIndirectMethod( "deliver" )
            },
            delay: delay
        });
    },


    "/chat": function( ev ) {
        var div = document.createElement( "div" );
        div.innerHTML = Template.process( "Foo", { data: [ ev ], div: div } );
        this.content.insertBefore( div, this.content.firstChild );
        //this.content.appendChild( div );
        //this.content.scrollTop = this.content.scrollHeight;
    },

    
    "/sixapart/atom": function( ev ) {
        /* todo fix on the server */
        var m = ev.data.match( /Title:\[\s(.+)\s\] Link:\[\s([^\s+]+)\s/ );
        if ( m && m[ 2 ] ) {
            var div = document.createElement( "div" );
            var s = m[ 2 ].match( /\.([^.]+\..{2,3})\// );
            if ( !s )
                s = [ '', '?' ];
            div.innerHTML = Template.process( "Atom", { site: s[ 1 ], title: m[ 1 ], link: m[ 2 ], div: div } );
            this.content.insertBefore( div, this.content.firstChild );
            var el = this.content.childNodes.length;
            if ( el > 20 ) {
                el -= 20;
                for ( var i = 0; i < el; i++ )
                    this.content.removeChild( this.content.lastChild );
            }
            //this.content.scrollTop = this.content.scrollHeight;
        }
    }

} );


Cometd.Chat = new Class( Component, {

    initObject: function( name, templateName ) {
        Cometd.Chat.superClass.initObject.apply( this, arguments );
        
        this.templateName = templateName || "Chat";
        
        this.defaultTextSet = true;
        this.inputElement = $( this.element.id + "-input" );
    },
    
    
    destroyObject: function( name, template ) {
        this.dialogs = null;
        this.inputElement = null;
        Cometd.Chat.superClass.destroyObject.apply( this, arguments );
    },
    

    initComponents: function() {
        Cometd.Chat.superClass.initComponents.apply( this, arguments );
        
        if ( this.inputElement.value != this.getDefaultInputText() ) {
            DOM.removeClassName( this.inputElement, "input-default" );
            this.defaultTextSet = false;
        } else {
            this.inputElement.value = this.getDefaultInputText();
        }
    },

    
    initEventListeners: function() {
        Cometd.Chat.superClass.initEventListeners.apply( this, arguments );
        
        if ( this.inputElement ) {
            this.addEventListener( this.inputElement, "focus", "inputFocus" );
            this.addEventListener( this.inputElement, "blur", "inputBlur" );
        }

        /*
        var form = $( this.element.id + "-form" );
        if ( form )
            this.addEventListener( form, "submit", "eventSubmit", true );
        */
    },


    destroyObject: function() {
        this.inputElenent = null;
        Cometd.Chat.superClass.destroyObject.apply( this, arguments );
    },


    /* events */
    
    eventClick: function( event ) {
        var command = this.getMouseEventCommand( event );
        switch( command ) {
            case "submit":
                this.eventSubmit( event );
                break;
        }
    },

    
    eventSubmit: function( event ) {
        var type;
        if ( this.defaultTextSet || this.inputElement.value == '' )
            return event.stop();
    },
    

    eventChange: function( event ) {
        if ( this.defaultTextSet )
            this.inputElement.value = this.getDefaultInputText();
    },
    
    
    inputFocus: function( event ) {
        if ( !this.defaultTextSet )
            return;

        this.inputElement.value = "";
        DOM.removeClassName( this.inputElement, "input-default" );
        this.defaultTextSet = false;
    },


    inputBlur: function( event ) {
        if ( this.inputElement.value != "" )
            return;
        
        DOM.addClassName( this.inputElement, "input-default" );
        this.inputElement.value = this.getDefaultInputText();
        this.defaultTextSet = true;
    },
    

    getDefaultInputText: function() {
        return "default";
    }

});

/*
EventSystem = {

    deliver: function( ev ) {
        if ( !( ev instanceOf Array ) )
            ev = [ ev ];
        for ( var i = 0; i < ev.length; i++ ) {
            if ( !ev[ i ].hasOwnProperty( "channel" ) )
                continue;
                
            this.isIndirectMethod( ev[ i ].channel );
        }
    },


    isIndirectMethod: function( method ) {
        if( !this.indirectMethods )
            this.indirectMethods = {};
        var m = this[ mn ];
        if( typeof m != "function" )
            return undefined;
        var indirectIndex = this.getIndirectIndex();
        //if( !this.indirectMethods[ mn ] )
    }

};
*/
