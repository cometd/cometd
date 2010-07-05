/*
Timer Library
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


Timer = new Class( Object, {
    init: function( callback, delay, count ) {
        this.callback = callback;
        this.delay = max( delay, 1 );
        this.nextDelay = this.delay;
        this.startTime = 0;
        this.count = count;
        this.execCount = 0;
        this.state = "new";
        this.timeout = null;
        this.id = Unique.id();
        this.start();
    },
    
    
    destroy: function() {
        this.stop();
        this.callback = null;
    },
    
    
    exec: function() {
        this.execCount++;
        this.callback( this );
        if ( this.count && this.execCount >= this.count ) {
            return this.destroy();
        }
        if( this.state == "started" )
            this.run();
    },
    
    
    run: function() {
        this.state = "started";
        this.timeout = window.setTimeout( this.exec.bind( this ), this.nextDelay );
        this.nextDelay = this.delay;
        var date = new Date();
        this.startTime = date.UTC;
    },
    
    
    start: function() {
        switch( this.state ) {
            case "new":
            case "paused":
            case "stopped":
                this.run();
                break;
        }
    },
    
    
    stop: function() {
        this.state = "stopped";
        try {
            window.clearTimeout( this.timeout );
        } catch( e ) {}
        this.timeout = null;
    },
    
    
    pause: function() {
        if( this.state != "started" )
            return;
        this.stop();
        this.state = "paused";
        var date = new Date();
        this.nextDelay = max( this.delay - (date.UTC - this.startTime), 1 );
    },


    reset: function( delay ) {
        if( this.state != "started" )
            return;
        if( defined( delay ) )
            this.delay = this.nextDelay = max( delay, 1 );
        try {
            window.clearTimeout( this.timeout );
        } catch( e ) {}
        this.timeout = window.setTimeout( this.exec.bind( this ), this.nextDelay );
    }
    
} );
