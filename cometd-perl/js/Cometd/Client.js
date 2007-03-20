Cometd = { };

Cometd.Client = new Class( Client, {
    "clientId": null,
    "authToken": null,

    "minimumProtocolVersion": .1,
    "protocolVersion": .2,
    "supportedConnectionTypes": [ "http-polling" ],

    handshake: function( obj ) {
        obj.client = this;
        obj.request = [
            {
                "channel": "/meta/handshake",
                "version": this.protocolVersion,
                "minimumVersion": this.minimumProtocolVersion,
                "supportedConnectionTypes": this.supportedConnectionTypes
            }
        ];
        if ( obj.authScheme )
            obj.request[ 0 ].authScheme = obj.authScheme;
        if ( obj.authUser )
            obj.request[ 0 ].authUser = obj.authUser;
        if ( obj.clientId )
            this.clientId = obj.clientId;
        if ( this.clientId )
            obj.request[ 0 ].clientId = this.clientId;
        
        if ( obj.authToken )
            this.authToken = obj.authToken;
        if ( this.authToken )
            obj.request[ 0 ].authToken = this.authToken;

        return new this.constructor.Request( obj );
    },


    connect: function( obj ) {
        obj.client = this;
        // TODO connectionType from response
        obj.request = [
            {
                "channel": "/meta/connect",
                "clientId": this.clientId,
                "connectionType": "http-polling"
            }
        ];
        if ( obj.clientId )
            obj.request[ 0 ].clientId = obj.clientId;
        if ( obj.authToken )
            this.authToken = obj.authToken;
        if ( this.authToken )
            obj.request[ 0 ].authToken = this.authToken;
        
        return new this.constructor.Request( obj );
    },


    reconnect: function( obj ) {
        obj.client = this;
        obj.request = [
            {
                "channel": "/meta/reconnect",
                "clientId": this.clientId,
                "connectionType": "http-polling",
                "timestamp": ( new Date ).toString()
            }
        ];
        //"id":           "LastReceivedMessageId",
        //"connectionId": "/meta/connections/26",
        if ( obj.clientId )
            obj.request[ 0 ].clientId = obj.clientId;
        if ( obj.authToken )
            this.authToken = obj.authToken;
        if ( this.authToken )
            obj.request[ 0 ].authToken = this.authToken;

        return new this.constructor.Request( obj );
    }

} );


Cometd.Client.Request = new Class( Client.Request, {
    contentType: "application/x-www-form-urlencoded",
    postContentType: "text/javascript+json",

    init: function( obj ) {
        this.state = "new";
        this.client = obj.client;
        this.heap = obj.heap;
        this.id = defined( this.heap )
            ? this.client.getUniqueId()
            : null;
        this.asynchronous = this.heap ? true : false;

        /* this.request is an array */
        this.request = obj.request;
 
        if( obj.delay ) {
            this.timer = new Timer( this.start.bind( this ), obj.delay, 1 );
        } else
            this.start();
    },
    

    start: function() {
        this.timer = null;
        this.state = "started";
        
        this.transport = new XMLHttpRequest();
        if( this.id != null )
            this.transport.onreadystatechange = this.readyStateChange.bind( this );
            
        this.transport.open( "POST", this.client.url, this.asynchronous, this.client.user, this.client.password );
        this.transport.setRequestHeader( "content-type", this.contentType );
   
        var json = this.request.toJSON();
        log.debug( "request:" + json ); 
        this.transport.send( "message=" + encodeURI( json ) );
    },
    

    readyStateChange: function() {
        if( this.transport.readyState != 4 || this.state != "started" )
            return;
        
        this.state = "finished";
        this.response = {
            id: this.id,
            result: null,
            error: null
        };

        this.transport.responseText.replace( /^message=/, "" );
        
        try {
            if ( this.transport.responseText.charAt(0) == "[" ) {
                try {
                    this.response.result = eval( "(" + this.transport.responseText + ")" );
                } catch( e ) {
                    this.response.error = "error in eval/parse of responseText";
                }
            } else {
                this.response.error = "Status: " + this.transport.status + " Error: Response not in JSON format or not Bayeux protocol";
                log.error( this.transport.responseText.encodeHTML() );
            }
        } catch( e ) {
            if ( e.message )
                e = e.message;
            this.response.error = e;
        }
        if ( this.response && this.response.status )
           this.response.status = this.transport.status;

        if ( this.response.result && this.response.result[ 0 ] ) {

            var res = this.response.result[ 0 ];
            if ( res.hasOwnProperty( "channel" ) && res.channel.match( /^\/meta\// ) ) {
                
                if ( res.hasOwnProperty( "clientId" ) ) {
                    this.client.clientId = res.clientId;
//                    log.debug('found client id in response: ' + this.client.clientId);
                }
                        

                if ( res.hasOwnProperty( "authToken" ) ) {
                    this.client.authToken = res.authToken;
//                    log.debug('found auth token: ' + this.client.authToken);
                }
                
                if ( res.channel.match( /^\/meta\/handshake/ ) ) {
                    if ( !( res.hasOwnProperty( "authSuccessful" ) && res.authSuccessful ) ) {
                        this.response.error = res.error;
                        log.error('error: ' + this.response.error);
                    }
                }
            
                if ( res.hasOwnProperty( "successful" ) && !res.successful ) {
                    this.response.error = res.error;
                    log.error('error: ' + this.response.error);
                }
            }
        }
        
        log.debug( 'response:' + this.response.toJSON() );

        if( this.heap && this.heap.callback )
            if ( this.processCallbacks( this.heap.callback ) )
                return;

        this.heap = null;
        this.client = null;
    }

} );
