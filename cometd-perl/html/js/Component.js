/**
DOM Component Library - Copyright 2005 Six Apart
$Id$

Copyright (c) 2005, Six Apart, Ltd.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.

    * Neither the name of "Six Apart" nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 * <br/><br/>
 * <hr><br/><br/>
 * Class <code>Component</code> class.<br/><br/>
 * @object-prop <code>sentinels</code> <code>Array</code>  A collection of 
 *              hidden html input fields that bestow tabbability and modality upon components.<br/>
 * @object-prop <code>ativatable</code> <code>boolean</code> Whether or not this component could
 *              be made active -- see below.<br/>
 * @object-prop <code>ative</code> <code>boolean</code> Whether or not this component is active -- active 
 *              components are managed by <code>App</code>.<br/>
 * @object-prop <code>modal</code> <code>boolean</code> Whether or not this component is modal 
 *              (is the only part of the application that may be currently interacted with by the user)
 *               -- modal components are managed by <code>App</code>.<br/>
 * @object-prop <code>focusableTagNames</code> <code>Object</code>A dictionary of names of html tags that are 
 *              allowed focus under the 'active' state and under modality.  Used for controlling focus.<br/>
 * <br/><hr/>
 * Class <code>Modal</code>  The modal pop-up superclass.<br/>
 * @inherits-from <code>Component</code>.<br/>
 * <hr/>
 * Class <code>Transient</code>  A transient modal pop-up, such as a list or color picker.<br/>
 * @inherits-from <code>Modal</code>.<br/>
 * @listens-to <code>onkeypress</code> <br/>
 * <hr/>
 * Class <code>Modal-Message</code>  A  modal pop-up message, such as a confirm dialog.<br/>
 * @inherits-from <code>Modal</code>.<br/>
 * @listens-to <code>onkeypress</code> <br/>
 * <hr/>
 * Class <code>ModalMask</code>  The event-blocking mask that preserves the modality of modals 
 *        from clicking outside their boundaries.<br/>
 * <br/><br/>
 */

Component = new Class( Observer, Autolayout, {
    useClosures: false,
    
    
    init: function() {
        this.initObject.apply( this, arguments );
        this.initSentinels();
        this.initEventListeners();
        this.initComponents();
    },
    
    
    destroy: function() {
        arguments.callee.applySuper( this, arguments );
        this.destroyComponents();
        this.destroyEventListeners();
        this.destroyObject();
    },
    
    
    initObject: function( element ) {
        arguments.callee.applySuper( this, arguments );
        this.element = DOM.getElement( element );
        if( !this.element )
            throw typeof element == "string"
                ? "no element: " + element
                : "no element";
        this.name = element;
        this.parent = null;
        this.eventListeners = {};
        this.components = [];
        this.sentinels = null;
        this.active = false;
    },
    
    
    destroyObject: function( element ) {
        this.components = null;
        this.eventListeners = null;
        this.sentinels = null;
        this.parent = null;
        this.element = null;
        this.name = null;
    },
    
    
    /* events */
    
    initEventListeners: function() {
        this.addEventListener( this.element, "mouseover", "eventMouseOver" );
        this.addEventListener( this.element, "mouseout", "eventMouseOut" );
        this.addEventListener( this.element, "mousemove", "eventMouseMove" );
        this.addEventListener( this.element, "mousedown", "eventMouseDown" );
        this.addEventListener( this.element, "mouseup", "eventMouseUp" );
        this.addEventListener( this.element, "click", "eventClick" );
        this.addEventListener( this.element, "dblclick", "eventDoubleClick" );
        this.addEventListener( this.element, "contextmenu", "eventContextMenu" );
        this.addEventListener( this.element, "keydown", "eventKeyDown" );
        this.addEventListener( this.element, "keyup", "eventKeyUp" );
        this.addEventListener( this.element, "keypress", "eventKeyPress" );
        this.addEventListener( this.element, "focus", "eventFocus", true ); // FIXME: why are these capturing?
        this.addEventListener( this.element, "blur", "eventBlur", true ); // FIXME: why are these capturing?
        this.addEventListener( this.element, "focusin", "eventFocusIn" );
        this.addEventListener( this.element, "focusout", "eventFocusOut" );
    },
    

    destroyEventListeners: function() {
        this.eventListeners.destroy();
    },
    
    
    addEventListener: function( object, eventName, methodName, useCapture ) {
        if ( !this[ methodName ] || this[ methodName ] === Function.stub ) /* XXX - purge instances of Function.stub */
            return;
        DOM.addEventListener( object, eventName, 
            (this.useClosures
                ? this.getEventListener( methodName )
                : this.getIndirectEventListener( methodName ) ),
            useCapture );
    },

    
    removeEventListener: function( object, eventName, methodName, useCapture ) {
        var listener = this.useClosures
            ? this.getEventListener( methodName )
            : this.getIndirectEventListener( methodName );
        DOM.removeEventListener( object, eventName, listener, useCapture );
    },
    

    matchCommand: /(?:^|\s)command-(\S+)(?:\s|$)/,


    getMouseEventCommand: function( event, rootElement ) {
        var ancestors = DOM.getAncestors( event.target, true );
        for( var i = 0; i < ancestors.length; i++ ) {
            try {
                var result = this.matchCommand.exec( ancestors[ i ].className );
                if( result[ 1 ] ) {
                    event.commandElement = ancestors[ i ];
                    /* foo-bar -> fooBar */
                    event.command = result[ 1 ].cssToJS(); 
                    return event.command;
                }
                if ( ancestors[ i ] == rootElement )
                    break;
            } catch( e ) {}
        }
    },


    /* event listeners */

    eventMouseDown : function( event ) {
        if( this.activatable ) 
            this.activate( event );
    },

    

    eventFocus: function( event ) {
        if( this.activatable ) 
            this.activate( event );
    },


    eventFocusIn: function( event ) {
        if( this.activatable ) 
            this.activate( event );
    },
    
    
    /* layout */
        
    reflow: function( event ) {
        this.applyAutolayouts( this.element );
        this.reflowComponents( event );
    },
    
    
    reflowComponents: function( event ) {
        this.components.forEach( function( component ) { component.reflow( event ); } );
    },
    
    
    /* components */
    
    initComponents: function() {},
    
    
    destroyComponents: function() {
        this.components.forEach( function( component ) { component.destroy(); } );
        this.components.length = 0;
    },
    
    
    addComponent: function( component ) {
        this.components.add( component );
        component.parent = this;
        component.reflow();
        return component;
    },
    
    
    removeComponent: function( component ) {
        this.components.remove( component );
        component.parent = null;
        return component;
    },
    

    /*  active component functionality (including modality) */
  
    activatable: false,
    modal: false,
    
    
    /**
     * class <code>Component</code>
     * Creates the invisible text input elements used as focus sinks for tabbability and modality 
     * (modal <code>div</code> "windows", etc).  These text input elements are arranged in a 
     * specific way so that, for modal components, they may trap tabbing events to keep tab focus
     * within the modal component.  When it receives focus, the last sentinal 'punts' focus 
     * back to the first sentinel.
     */    
    initSentinels: function() {
        if( !this.activatable || this.sentinels )
            return;
        
        this.sentinels = {
            captureStart: DOM.createInvisibleInput()
        };

        this.element.insertBefore( this.sentinels.captureStart, this.element.firstChild );
        
        if( !this.modal )
            return;
        
        extend( this.sentinels, {
            puntStart: DOM.createInvisibleInput(), 
            captureEnd: DOM.createInvisibleInput(),
            puntEnd: DOM.createInvisibleInput()
        } );

        this.element.insertBefore( this.sentinels.puntStart, this.element.firstChild );
        this.element.appendChild( this.sentinels.captureEnd );
        this.element.appendChild( this.sentinels.puntEnd );
    },


    /**
     * class <code>Component</code>
     * Create new sentinels.  This is necessary in case their html must be destroyed.
     */
    refreshSentinels: function() {
        this.sentinels = null;
        this.initSentinels();
    },


    /**
     * class <code>Component</code>     
     * Activate this component (can be either modal or 'plain' active).
     */
    activate: function( event ) {
        if( !this.activatable ) 
            return; 
        if( !window.app.setActiveComponent( this ) ) 
           return;
          
        this.active = true;
        this.captureFocus( event );
        DOM.addClassName( this.element, "active-component" );
        this.broadcastToObserversNB( "componentActivated", this );
    },


    /**
     * class <code>Component</code>
     * De-activate this component (can be either modal or 'plain' active).
     */
    deactivate: function() {
        this.active = false;
        DOM.removeClassName( this.element, "active-component" );
        this.broadcastToObserversNB( "componentDeactivated", this );
    },
    

    focusableTagNames: { // Note: All elements with 'mouse event commands' are focusable (see 'captureFocus').
        taginput: defined,
        tagtextarea: defined,
        tagbutton: defined,
        tagselect: defined,
        tagdiv: defined, // FF 1.5+ puts divs in the tabbing order. Added 2006-05-03: See case 27751 and case 34362.
        taga: defined, // 'anchor' tag.  Added 2006-05-03: See Case 27751.
        tagoption: defined,
        tagp: defined // case 36542
    },


    /** 
     * class <code>Component</code>
     * Capture and manage the focus on an activatable (optionally including modal) component.
     * The behavior differs for the various types of activatable component.
     * @param event <code>Event</code> The event that leads to focus (i.e., a tab or a click).
     */        
    captureFocus: function( event ) {
        if ( this.active && !this.modal )
            return;
        var tagName = ( defined( event ) && event.target.tagName )
            ? "tag" + event.target.tagName.toLowerCase()
            : null;
        var command;
        if ( tagName && event ) 
           command = this.getMouseEventCommand( event ); // Avoid scrolling out from over command elements.
        // Set the tabbing focus on the sentinel, unless the user wants it on another form or command element: 
        if( !tagName || ( this.focusableTagNames[ tagName ] !== defined ) && !command ) {
            try {
                this.sentinels.captureStart.focus();
                event.stop(); // Force the action to stop at the focus set above.
                if( defined( this.sentinels.captureStart.focusIn ) )
                    this.sentinels.captureStart.focusIn();
            } catch ( e ) {}
        }

        if( !defined( event ) || !this.modal )
            return;
        /*-
         * As of Firefox 1.5.0.6 at least, bug where key events could not be heard from divs has been fixed.
         * Workaround code for this put in on 2006-05-03 and removed 2006-08-24.  See case 27751 and case 34362.
         */
        try {
            if( event.target === this.sentinels.puntEnd )
                this.sentinels.captureStart.focus();
            else if( event.target === this.sentinels.puntStart )
                this.sentinels.captureEnd.focus();
        } catch( e ) {}
    },
    

    /* misc */
    
    hide: function() {
        DOM.removeClassName( this.element, "visible" );
        DOM.addClassName( this.element, "hidden" );
    },
    
    
    show: function() {
        DOM.removeClassName( this.element, "hidden" );
        DOM.addClassName( this.element, "visible" );
        this.reflow();
    }
} );


/* modal subclass */

Modal = new Class( Component, {
    activatable: true,
    modal: true,
    isOpen: false,
    
    /* execution */
    
    open: function( data, callback ) {
        if ( !this.transitory ) 
            window.scrollTo( 0, 0 );
        this.data = defined( data ) ? data : {};
        this.callback = callback;
        this.active = false;
        this.isOpen = true;
        window.app.addModal( this );        
    },
    
    
    close: function( data ) {
        window.app.removeModal( this );
        this.isOpen = false;
        if( this.callback )
            this.callback( data, this );
        this.callback = null;
        this.data = null;
    },
    

    eventClick: function( event ) {
        if ( event.shiftKey ) 
            event.stop();
    },

    
    eventKeyPress: function( event ) {
        switch( event.keyCode ) {
            case 27:
                this.close( false );
        }
    }
} );


/* transient subclass */

Transient = new Class( Modal, {
    transitory: true,
    
    /* events */
    
    /**
     * Class: <code>Transient</code><br>
     * This method allows a <ocde>Transient</code> to disappear on keypress of 'esc'..
     * @param event <code>Event</code>  A prepared (processed by the custom js framework) <code>Event</code> object.
     */
    eventKeyPress: function( event ) {
        switch( event.keyCode ) {
            case 27:
                this.close( false ); 
        }
    },
    

    /**
     * Returns the command from the event, via <code>getMouseEventCommand</code>.
     * @param event  <code>Event</code> A prepared event object.
     */
    eventClick: function( event ) {
        this.close( this.getMouseEventCommand( event ) );
        return event.stop();
    },
    
    
    eventContextMenu: function( event ) {
        return event.stop();
    },

    
    open: function( data, callback, targetElement ) {
        this.targetElement = targetElement;
        return arguments.callee.applySuper( this, arguments );
    }
} );


Component.Delegator = {
    
    DEFAULT_NAMESPACE: "core",

    setupDelegates: function( object ) {
        /* this needs more testing before enabling
        if ( object && !object.delegateParent )
            object.delegateParent = this;
        */
        
        if ( !this.delegateListeners )
            this.delegateListeners = {};
            
        if ( !this.delegates )
            this.delegates = {};

        if ( !defined( this.NAMESPACE ) )
            this.NAMESPACE = ( window.app && app.NAMESPACE )
                ? app.NAMESPACE : this.DEFAULT_NAMESPACE;
    },
    

    addEventListener: function( object, eventName, methodName, useCapture ) {
        DOM.addEventListener( object, eventName, 
            (this.useClosures
                ? this.getEventListener( methodName )
                : this.getIndirectEventListener( methodName ) ),
            useCapture );
    },


    /* delegate functions */
    setDelegate: function( name, object ) {
        this.setupDelegates( object );
        this.delegates[ name ] = object;
        return object;
    },
    
    
    setDelegateListener: function( eventName, delegateName ) {
        this.setupDelegates();
        this.delegateListeners[ eventName ] = delegateName;
    },
    
    
    delegateEvent: function( event, eventName ) {
        var delegate = DOM.getMouseEventAttribute( event, this.NAMESPACE + ":delegate" );
            
        if ( !delegate ) {
            if ( this.delegateListeners && this.delegateListeners.hasOwnProperty( eventName ) )
                delegate = this.delegateListeners[ eventName ];
            else
                return undefined;
        } else
            delegate = delegate.cssToJS();
        
        if ( this.delegates && this.delegates.hasOwnProperty( delegate ) && this.delegates[ delegate ][ eventName ] )
            return this.delegates[ delegate ][ eventName ]( event, this );
    },
    
    
    getIndirectEventListener: function( methodName ) {
        if( !this.indirectEventListeners )
            this.indirectEventListeners = {};
        var method = this[ methodName ];
        var indirectIndex = this.getIndirectIndex();
        if( !this.indirectEventListeners[ methodName ] ) {
            return this.indirectEventListeners[ methodName ] = new Function( "event",
                "try { event = Event.prep( event ); } catch( e ) {}" +
                "var o = indirectObjects[" + indirectIndex + "];" +
                "var r = o.delegateEvent( event, '" + methodName +
                "' ); if ( r ) return r; if ( o[ '" + methodName +
                "' ] ) return o." + methodName + ".call( o, event );" );
        }
        
        return this.indirectEventListeners[ methodName ];
    }
};
