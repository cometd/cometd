/*
Observer Library
$Id$

Copyright (c) 2006, Six Apart, Ltd.
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


Observer = new Class( EventDispatcher, {
    init: function() {
        this.observers = [];
    },
    
    
    destroy: function() {
        if( this.observers )
            this.observers.length = 0;
        this.observers = undefined;
    },
    
    
    addObserver: function( object ) {
        if (!this.observers)
            this.observers = [];
        this.observers.add( object.getIndirectIndex() );
    },
    
    
    removeObserver: function( object ) {
        if (!this.observers)
            return;
        this.observers.remove( object.getIndirectIndex() );
    },
    
    
    broadcastToObservers: function() {
        if (!this.observers)
            return;

        var args = Array.fromPseudo( arguments );
        var methodName = args.shift();
        
        for ( var i = 0; i < this.observers.length; i++ ) {
            var observer = indirectObjects[ this.observers[ i ] ];
            if ( observer[ methodName ] )
                observer[ methodName ].apply( observer, args );
        }
    },
   
    
    /* non blocking broadcast. used in event chains where speed is key */
    broadcastToObserversNB: function() {
        if (!this.observers)
            return;

        var args = Array.fromPseudo( arguments );
        var methodName = args.shift();
        
        for ( var i = 0; i < this.observers.length; i++ ) {
            if ( indirectObjects[ this.observers[ i ] ][ methodName ] ) {
                var _ob = indirectObjects[ this.observers[ i ] ];
                var method = _ob[ methodName ];
                new Timer( function() { method.apply( _ob, args ); }, ( ( i * 10 ) + 10 ), 1 );
            }
        }
    }
});
