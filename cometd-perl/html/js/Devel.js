/*
Development Library - Copyright 2005 Six Apart
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


/* benchmarking */

benchmark = function( callback, iterations ) {
    var start = new Date();
    for( var i = 0; i < iterations; i++ )
        callback();
    var end = new Date();
    return (end.getSeconds() - start.getSeconds()) +
        (end.getMilliseconds() - start.getMilliseconds()) / 1000;
}


inspect = function( object, allProperties, noBreaks ) {
    var out = "";
    for( var property in object ) {
        try {
            if( !allProperties && !object.hasOwnProperty( property ) )
                continue;
            out += property + ": " + object[ property ] +
                (noBreaks ? "\n" : "<br />");
        } catch( e ) {}
    }
    return out;
}


/* logging, alert override */

Logger = new Class( Object, {
    width: 320,
    height: 240,
    windowName: "log",
    colors: {
        INFO: "#000",
        ERROR: "#f00",
        DEBUG: "#005f16",
        WARN: "#2634cf"
    },


    log: function() {        
        this.logMessages( "INFO", arguments );
    },
    
    
    logError: function() {
        this.logMessages( "ERROR", arguments );
    },
    
    
    logWarn: function() {
        this.logMessages( "WARN", arguments );
    },


    logDebug: function() {
        this.logMessages( "DEBUG", arguments );
    },
    
    
    logMessages: function( type, messages ) {
        try {
            var message = "";
            for( var i = 0; i < messages.length; i++ )
                message += messages[ i ];

            if ( window.controllers && window.console && window.console.log ) {
                window.console.log( '[' + type + '] - ' + message );
                return;
            }

            // create window
            this.createWindow();

            // check for no window
            if( !this.window ) {
                confirm( "Logger popup window blocked. Using confirm() instead.\n\n" + msg );
                return true;
            }

            // create div
            var div = this.window.document.createElement( "div" );
            div.style.color = this.colors[ type ] || "#000";
            div.style.backgroundColor = (this.count % 2) ? "#eee" : "#fff";
            div.style.width = "auto";
            div.style.padding = "3px";
            div.innerHTML = message;

            // append to window
            this.window.document.body.appendChild( div );
            this.window.scroll( 0, this.window.document.body.scrollHeight );
            this.count++;
            return true;
        } catch( e ) {}
    },
    

    createWindow: function() {
        if( this.window && this.window.document )
            return;
        
        // create window
        var x = "auto";
        var y = "auto";
        var attr = "resizable=yes, menubar=no, location=no, directories=no, scrollbars=yes, status=no, " +
            "width=" + this.width + ", height=" + this.height + 
            "screenX=" + x + ", screenY=" + y + ", " +
            "left=" + x + ", top=" + y + ", "; 
        // 2006-01-19 cmb For WebKit debugging, change "" below to "/logger".
        this.window = window.open( "", this.windowName, attr );
        
        // check for blocked popup
        if( !this.window )
            return;
        
        // for safari
        window.top.focus();
        
        var instance;
        try {
            instance = this.window.__Logger;
        } catch( e ) {
            this.window.location.replace( "about:blank" );
        }
        
        // check for pre-existing instance
        if( instance ) {
            // create divider div
            var div = this.window.document.createElement( "div" );
            div.style.backgroundColor = "#f00";
            div.style.width = "auto";
            div.style.height = "2px";
            div.style.fontSize = "0.1px";
            div.style.lineHeight = "0.1px";
            this.window.document.body.appendChild( div );
        } else {
            // write body
            this.window.document.open( "text/html", "replace" );
            this.window.document.write( "<html><head><title>JavaScript Log</title></head><body ondblclick=\"document.body.innerHTML='';\"></body></html>" );
            this.window.document.close();
            
            // setup style
            this.window.title = "JavaScript Loggers";
            this.window.document.body.style.margin = "0";
            this.window.document.body.style.padding = "0";
            this.window.document.body.style.fontFamily = "verdana, 'lucida grande', geneva, arial, helvetica, sans-serif";
            this.window.document.body.style.fontSize = "10px";
        }
        
        // get previous instance and attach new instance
        this.prev = instance;
        this.window.__Logger = this;
        
        // dereference previous previous
        if( this.prev )
            this.prev.prev = null;
        
        // copy message count
        this.count = this.prev ? this.prev.count : 0;
    }
} );


override( Logger, {
    log: function() {
        var a = Logger.logMessages( "INFO", arguments );
        return a; 
    },
    
    
    logError: function() {
        return Logger.logMessages( "ERROR", arguments );
    },
    
    
    logWarn: function() {
        return Logger.logMessages( "WARN", arguments );
    },


    logDebug: function() {
        return Logger.logMessages( "DEBUG", arguments );
    },
    
    
    logMessages: function( type, messages ) {
        Logger.initSingleton();
        return Logger.singleton.logMessages( type, messages );
    }
} );

log = Logger.log;
log.error = Logger.logError;
log.warn = Logger.logWarn;
log.debug = Logger.logDebug;
