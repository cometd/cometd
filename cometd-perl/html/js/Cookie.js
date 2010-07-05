/*
Cookie JavaScript Library
$Id$

Copyright (c) 2007, Six Apart, Ltd.
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

/* constructor */

Cookie = new Class ( Object, {
    
    /**
     * See <code>Cookie.bake</code> for doc on instantiation.  This is a standard framework method.<br><br>
     */
    init: function( name, value, domain, path, expires, secure ) {
        this.name = name;
        this.value = value;
        this.domain = domain;
        this.path = path;
        this.expires = expires;
        this.secure = secure;
    },


    /**
     * Get this cookie from the web browser's store of cookies.  Note that if the <code>document.cookie</code>
     * property has been written to repeatedly by the same client code in excess of 4K (regardless of the size
     * of the actual cookies), IE 6 will report an empty <code>document.cookie</code> collection of cookies.
     * @return <code>Cookie</code> The fetched cookie.
     */
    fetch: function() {
        var prefix = escape( this.name ) + "=";
        var cookies = ("" + document.cookie).split( /;\s*/ );
        
        for( var i = 0; i < cookies.length; i++ ) {
            if( cookies[ i ].indexOf( prefix ) == 0 ) {
                this.value = unescape( cookies[ i ].substring( prefix.length ) );
                return this;
            }
        }
                                 
        return undefined;
    },

    
    /**
     * Set and store a cookie in the the web browser's native collection of cookies.
     * @return <code>Cookie</code> The set and stored ("baked") cookie.
     */
    bake: function( value ) {
        if( !defined( this.name ) || this.name == null ||
            ( ( !defined( this.value ) || this.value == null ) && ( !defined( value ) || value == null ) ) )
        return undefined;
        
        var name = escape( this.name );

        if ( !defined( value ) || value == null ) 
            value = this.value;
        else 
            this.value = value;

        value = escape( value );
        
        // log( "Saving value: " + value );
        var attributes = ( this.domain ? "; domain=" + escape( this.domain ) : "") +
            (this.path ? "; path=" + escape( this.path ) : "") +
            (this.expires ? "; expires=" + this.expires.toGMTString() : "") +
            (this.secure ? "; secure=1"  : "");       

        
        var batter = name + "=" + value + attributes;                   
        document.cookie = batter;

        return this;
    },


    remove: function() {
        this.expires = new Date( 0 ); // "Thu, 01 Jan 1970 00:00:00 GMT"
        this.value = "";
        this.bake();     
    }
} );


/* - -  Static methods  - - */

override( Cookie, { 
    fetch: function( name ) {
        var cookie = new this( name );
        return cookie.fetch();        
    },

    
    bake: function( name, value, domain, path, expires, secure ) {
        var cookie = new this( name, value, domain, path, expires, secure );
        cookie.bake();
        return cookie;
    },


    remove: function( name ) {
        this.fetch( name ).remove();
    }  
} );
