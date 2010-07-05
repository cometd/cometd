/**
 * App Library - Copyright (c) 2006 Six Apart
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 * 
 *     * Neither the name of "Six Apart" nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * 
 * This file holds the <code>App</code> class.<br>
 * @object-prop <code>activeComponent</code> <code>Component</code> The currently active component in the 
 *              application.  This can be a panel in the application, a modal dialog, or 
 *              some other component.<br>
 * @object-prop <code>modalStack</code> <code>Array</code> An array of modal dialog components.<br>
 * @object-prop <code>modalMask</code> <code>Component</code> A div that is used when a modal dialog is displayed 
 *              to block events from reaching non-modal components.<br>
 * <br>
 * $Id$
 */


/* modal mask class */

ModalMask = new Class( Component, {
    eventMouseDown: function( event ) {
        window.app.dismissTransients();
    }
} );


/* app class */

App = new Class( Component, Component.Delegator, {
    NAMESPACE: "core",
        
    
    initObject: function() {
        arguments.callee.callSuper( this, document.documentElement );
        this.window = window;
        this.document = document;
        this.displayState = {};
        this.dialogs = {};
        this.flyouts = {};
        this.modalStack = [];
        this.activeComponent = null;
        this.monitorTimer = new Timer( this.getIndirectMethod( "monitor" ), 100 );
    },
    
    
    destroyObject: function() {
        this.modalStack = null;
        this.dialogs = null;
        this.flyouts = null;
        this.displayState = null;
        this.activeComponent = null;
        this.document = null;
        this.window = null;
        arguments.callee.applySuper( this, arguments );
    },


    /* events */

    initEventListeners: function() {
        arguments.callee.applySuper( this, arguments );
        this.addEventListener( window, "resize", "eventResize" );
        this.addEventListener( window, "unload", "eventUnload" );
    },
    
    
    eventClick: function( event ) {
        var command = this.getMouseEventCommand( event );
        switch( command ) {
            case "goToLocation":
                event.stop();
                this.gotoLocation( event.commandElement.getAttribute( "href" ) );
                break;
        }
    },
    

    /* event listeners */
    
    eventResize: function( event ) {
        return this.reflow( event );
    },
    
    
    eventUnload: function( event ) {
        this.destroy();
    },
    

    /* components */
    
    initComponents: function() {
        arguments.callee.applySuper( this, arguments );
//        this.modalMask = new ModalMask( "modal-mask" );
//        this.addComponent( this.modalMask );
    },
    
    
    /* timers */
    
    monitor: function( timer ) {
        this.monitorDisplayState();
        this.monitorLocation();
    },
    
    
    /* display state */
    
    monitorDisplayState: function() {
        var changed = 0;
        var style = DOM.getComputedStyle( this.element );
        changed += this.displayChanged( style, "fontSize" );
        if( changed )
            this.reflow();
    },
    
    
    displayChanged: function( object, property ) {
        try {
            if( this.displayState[ property ] != object[ property ] ) {
                this.displayState[ property ] = object[ property ];
                return 1;
            }
        } catch( e ) {}
        return 0;
    },
    
     
    /*  - - - - Begin activatables section - - - -  */
    
    /**
     * Get the currently active (shaded, having meta-focus, etc) component on the page.
     * @return <code>Component</code>
     */    
    getActiveComponent: function() {
        return this.activeComponent; 
    },


    /**
     * Event handler <b>not</b> based on <code>currentTarget</code>.
     * @param component <code>Component</code> The component that should be activated.
     * @return <code>boolean</code> Whether or not the <code>app</code> allowed the activation. 
     *         Currently, this always returns <code>true</code>. <br><br>TODO: Code the check for the following:
     *         While the modal mask prevents the user from messing with the currently active
     *         component when a modal dialog is up, we want to keep code from doing so as well just in case.
     */
    setActiveComponent: function( component ) {   
        var modal = this.modalStack[ this.modalStack.length - 1 ];
        var ancestors = DOM.getAncestors( component.element, true );
        
        if( modal && ancestors.indexOf( modal.element ) > 0 && modal.active )       
            return false; // Since we 'includeSelf' in the 'getAncestors' (above), *must* use '>', not '>='.

        if( this.activeComponent && this.activeComponent !== component )
            this.activeComponent.deactivate();
        this.activeComponent = component; 
        return true; 
    },

    
    /*  - Begin modal subsection -  */

    /**
     * Add a modal component to the collection of modal components managed by this <code>App</code>.
     * @param modal <code>Component</code> The modal component (i.e., a dialog) to be added.
     */
    addModal: function( modal ) {
        this.modalStack.add( modal );
        this.modalMask.show();
        modal.show();         
        this.stackModals();
    },
    
    
    /**
     * Remove a modal component from the collection of modal components managed by this <code>App</code>.
     * @param modal <code>Component</code> The modal component (i.e., a dialog) to be added.
     */
    removeModal: function( modal ) {
        modal.active = false;
        modal.hide();
        this.modalStack.remove( modal );
        this.stackModals();
    },
        

    /**
     * Order the modals visuallly (in the z-index) according to their order in the managed collection
     * of modal components.  Switch <code>modalMask</code> appropriately according to the nature of
     * the top-most modal element in the z-index.
     */
    
    STACK_RADIX: 1000,
    
    stackModals: function() {            
        for( var i = this.modalStack.length; i > 0; i-- )
            DOM.setZIndex( this.modalStack[ i - 1 ].element, i * this.STACK_RADIX );
        
        DOM.setZIndex( this.modalMask.element, this.modalStack.length * this.STACK_RADIX - 10 );
        if( this.modalStack.length ) {
            this.modalMask.show();
            this.modalStack[ this.modalStack.length - 1 ].activate(); // Keep the top one active.
        } else
            this.modalMask.hide();
    },
    

    dismissTransients: function() {
        for ( var i = this.modalStack.length - 1; i >= 0; i-- )
            if ( this.modalStack[ i ].transitory )
                this.modalStack[ i ].close();
    },


    /**
     * Remove all modal components, so that they do not show on the screen and 
     * and so that they do not exist in the managed collection (<code>modalStack</code>).
     */
    dismissAll: function() {
        while ( this.modalStack.length )
            this.removeModal( this.modalStack[ 0 ] );
    },

    /*  - End modal subsection -  */



    /*  - - - - End activatables section - - - -  */

    
    /* location monitoring, back/forward/bookmark in AJAX pages */
    
    gotoLocation: function( locationBase, locationArg ) {
        var location = ("" + this.window.location);
        var parts = location.split( "#" );
        if( locationBase ) {
            parts[ 0 ] = locationBase;
            this.monitorTimer.stop();
        }
        if( locationArg )
            parts[ 1 ] = encodeURI( this.encodeLocation( locationArg ) );
        else
            parts.length = 1;
            
        location = parts.join( "#" );
        
        if( this.location == location )
             return;
        this.location = location;
        
        // IE uses a hidden iframe
        if( !locationBase && defined( this.window.clipboardData ) ) {
            var iframe = this.document.getElementById( "__location" );
            iframe.contentWindow.document.open( "text/html" );
            iframe.contentWindow.document.write(
                "<script type='text/javascript'>" +
                "if( window.parent && window.parent.app )" +
                "window.parent.app.replaceLocation( '" + this.location + "' );" +
                "</script>" +
                "<body style='background: rgb(" +
                    Math.floor( Math.random() * 256 ) + "," +
                    Math.floor( Math.random() * 256 ) + "," +
                    Math.floor( Math.random() * 256 ) + ")'>" +
                this.location + "</body>"
            );
        }
        
        // otherwise just set the location
        else {
            this.window.location = this.location;
            /* safari back/forward is busted
             * since window.location isn't set immediately, monitorLocation
             * catches it later and fires exec again.  ALSO, safari never
             * changes window.location on back/forward, so monitorLocation
             * is useless.
             */
            if ( ( "" + this.window.location ) != this.location )
                this.monitorTimer.stop();
        }
        
        this.parseLocation();
        if ( !locationBase ) {
            this.exec();
            this.broadcastToObservers( "exec" );
        }
    },
   
    
    /* used by hidden iframe on IE */
    replaceLocation: function( location ) {
        this.window.location.replace( location );
        if( this.location == location )
            return;
        this.location = location;
        this.parseLocation();
        this.exec();
        this.broadcastToObservers( "exec" );
    },
    
    
    monitorLocation: function() {
        var location = ("" + window.location);
        if( this.location == location )
            return;
        this.location = location;
        this.parseLocation();
        this.exec();
        this.broadcastToObservers( "exec" );
    },
    
    
    parseLocation: function() {
        if( !defined( this.locationArg ) )
            this.locationArg = null;
        try {
            var parts = this.location.split( "#" );
            var arg = decodeURI( parts[ 1 ] || "" );
            this.locationArg = this.decodeLocation( arg );
        } catch( e ) {}
        return this.locationArg;
    },


    /* basic #/type:Music/page:2 encoding and decoding */
    
    encodeLocation: function( obj ) {
        var loc = [];
        for ( key in obj )
            if ( obj.hasOwnProperty( key ) && typeof obj[ key ] != "function" )
                loc.push( key + ":" + ( ( obj[ key ] instanceof Array ) ? obj[ key ].join( "," ) : obj[ key ] ) );
        return loc.join( "/" );
    },


    decodeLocation: function( arg ) {
        var obj = {};
        var vals = arg.split( "/" );
        var kv;
        for ( var i = 0; i < vals.length; i++ ) {
            if ( !defined( vals[ i ] ) )
                continue;
            kv = vals[ i ].split( ":" );
            /* xxx we don't decode comma seperated lists to arrays here */
            if ( kv && kv.length > 1 )
               obj[ kv[ 0 ] ] = kv[ 1 ];
            else
                obj[ vals[ i ] ] = true;
        }
        return obj;
    },

    
    /* execution */
    
    exec: function() {} 

});

    
/* bootstrap methods */

App.bootstrap = function() {
    window.app = App.initSingleton();
};


App.bootstrapInline = function( defer ) {
    /* deferred bootstrap onload by default */
    DOM.addEventListener( window, "load", this.bootstrap );
    this.deferBootstrap = defer;
    this.bootstrapApp();
};


App.bootstrapIframe = function() {
    this.deferBootstrap = false;
    this.bootstrapApp();
};


App.bootstrapCheck = function() {
    var e = DOM.getElement( "bootstrapper" );
    if ( !e )
        return log.warn('bootstrap checking...');
    
    if ( this.bootstrapTimer )
        this.bootstrapTimer.stop();
    
    this.bootstrapTimer = null;

    this.bootstrap();
}


App.bootstrapApp = function() {
    if ( this.deferBootstrap )
        return;
    this.bootstrapTimer = new Timer( this.bootstrapCheck.bind( this ), 20 );
};


/* creating the iframe during load is the only reliable way to preserve AJAX history
   across different page views. DOM created iframes do not work */

if( defined( window.clipboardData ) ) {
    /* fix IE background image flash */
    try {
        document.execCommand( "BackgroundImageCache", false, true );
    } catch( e ) { };
    document.write( "<iframe id='__location' src='about:blank' width='0' height='0' frameborder='0'" +
        "style='visibility:hidden;position:absolute;left:0;top:0;'></iframe>" );
}

