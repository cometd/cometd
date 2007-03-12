
Template.templates.Foo = '[# for ( var i = 0; i < data.length; i++ ) { #]<pre style="margin: 0px;">[#= data[ i ].toJSON() #]</pre>[# } #]';
            

App.singletonConstructor =
Cometd.Demo = new Class( App, {

    initObject: function() {
        Cometd.Demo.superClass.initObject.apply( this, arguments );

        this.client = new Cometd.Client( "/cometd" );
        this.content = $( "content" );
    },


    initComponents: function() {
        Cometd.Demo.superClass.initComponents.apply( this, arguments );

        this.chat = new Cometd.Chat( "chat-area" );
        this.addComponent( this.chat );
            
    },


    exec: function() {

        this.req = this.client.handshake({
            heap: {
                callback: this.getIndirectMethod( "handshake" )
            }
        });
    },
                

    handshake: function( res, obj ) {
        if ( res.error )
            return log.error( res.error );

        var div = document.createElement( "div" );
        div.innerHTML = Template.process( "Foo", { data: res.result, div: div } );
        this.content.appendChild( div );

        this.req = this.client.connect({
            heap: {
                callback: this.getIndirectMethod( "connect" )
            }
        });
    },
            

    connect: function( res, obj ) {
        var delay = 500;
        if ( res.error ) {
            log.error( res.error );
            delay = 5000;
        } else {
            var div = document.createElement( "div" );
            div.innerHTML = Template.process( "Foo", { data: res.result, div: div } );
            this.content.appendChild( div );
        }

        this.req = this.client.reconnect({
            heap: {
                callback: this.getIndirectMethod( "connect" )
            },
            delay: delay
        });
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
 
