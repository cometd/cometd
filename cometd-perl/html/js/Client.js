/*
Client Library
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

*/


/* core client object */

Client = new Class( Object, {

    init: function( url, user, password ) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.baseId = "c" + Unique.id();
        this.count = 0;
    },
    
    
    call: function( obj ) {
        return this.request( obj );
    },
    
    
    request: function( obj ) {
        obj.client = this;
        return new this.constructor.Request( obj );
    },
    
    
    getUniqueId: function() {
        return this.baseId + "r" + this.count++;
    }

} );


/* request object */

Client.Request = new Class( Observer, {
    contentType: "text/javascript+json",
    
    
    init: function( obj ) {
        this.state = "new";
        this.client = obj.client;
        this.method = obj.method || "default";
        this.params = obj.params || [];
        this.heap = obj.heap;
        this.id = defined( this.heap )
            ? this.client.getUniqueId()
            : null;
        this.asynchronous = this.heap ? true : false;
        
        this.request = {
            id: this.id,
            method: this.method,
            params: this.params
        };
        
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
    
        this.transport.send( Object.toJSON( this.request ) );
    },
    
    
    stop: function() {
        this.state = "stopped";
        this.heap = null;
        this.client = null;
        
        if( this.timer )
            this.timer.stop();
        if( this.transport )
            this.transport.abort();
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
        
        try {
            if ( this.transport.responseText.charAt(0) == "{" ) {
                try {
                    //this.response = Object.fromJSON( this.transport.responseText );
                    /*
                    if ( ( /^(\s+|[{}\[\]:,]|"(\\["\\\/bfnrtu]|[^\x00-\x1f"\\]+)*"|-?\d+(\.\d*)?([Ee][+-]?\d+)?|null|true|false)+$/.test(
                        this.transport.responseText
                    ) ) )
                    */
                        this.response = eval( "(" + this.transport.responseText + ")" );
                    /*
                    else
                        throw "response failed pre eval test";
                    */
                } catch( e ) {
                    this.response.error = "error in eval/parse of responseText";
                }
            } else {
                this.response.error = "Status: " + this.transport.status + " Error: Response not in JSON format";
                log.error( this.transport.responseText.encodeHTML() );
            }
        } catch( e ) {
            if ( e.message )
                e = e.message;
            this.response.error = e;
        }
        this.response.status = this.transport.status;
        
        if( this.heap && this.heap.callback ) {
            if ( this.processCallbacks( this.heap.callback ) )
                return;
        }

        this.heap = null;
        this.client = null;
    },

    
    processCallbacks: function( callbacks ) {
        /* support 1 or more callbacks */
        if ( callbacks instanceof Array ) {
            for ( var i = 0; i < callbacks.length; i++ ) {
                callbacks[ i ]( this.response, this.heap, this );
            }
        } else {
            callbacks( this.response, this.heap, this );
        }

        return false;
    },


    pause: function() {
        this.state = "paused";
        if( this.timer )
            this.timer.pause();
    }

} );
