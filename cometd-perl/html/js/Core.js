/*
Core JavaScript Library
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


/* stubs */

log = function() {};
log.error = log.warn = log.debug = log;


/* utility functions */

defined = function( x ) {
    return x === undefined ? false : true;
}


exists = function( x ) {
    return (x === undefined || x === null) ? false : true;
}


truth = function( x ) {
    return (x && x != "0") ? true : false; /* because "0" evaluates to true in javascript */
}


finite = function( x ) {
    return isFinite( x ) ? x : 0;
}


finiteInt = function( x, b ) {
    return finite( parseInt( x, b ) );
}


finiteFloat = function( x ) {
    return finite( parseFloat( x ) );
}


max = function() {
    var a = arguments;
    var n = a[ 0 ];
    for( var i = 1; i < a.length; i++ )
        if( a[ i ] > n )
            n = a[ i ];
    return n;
}


min = function() {
    var a = arguments;
    var n = a[ 0 ];
    for( var i = 1; i < a.length; i++ )
        if( a[ i ] < n )
            n = a[ i ];
    return n;
}


extend = function( o ) {
    var a = arguments;
    for( var i = 1; i < a.length; i++ ) {
        var s = a[ i ];
        for( var p in s ) {
            try {
                if( !o[ p ] && (!s.hasOwnProperty || s.hasOwnProperty( p )) )
                    o[ p ] = s[ p ];
            } catch( e ) {}
        }
    }
    return o;
}


override = function( o ) {
    var a = arguments;
    for( var i = 1; i < a.length; i++ ) {
        var s = a[ i ];
        for( var p in s ) {
            try {
                if( !s.hasOwnProperty || s.hasOwnProperty( p ) )
                    o[ p ] = s[ p ];
            } catch( e ) {}
        }
    }
    return o;
}


/* try block */  
 
Try = {
    these: function() {
        var a = arguments;
        for( var i = 0; i < a.length; i++ ) {
            try {
                return a[ i ]();
            } catch( e ) {}
        }
        return undefined;
    }
}


/* unique id generator */

Unique = {
    length: 0,
    
    id: function() {
        return ++this.length;
    }
}


/* shadow object */

Shadow = function( o ) {
    var a = arguments;
    for( var i = 0; i < a.length; i++ ) {
        try {
            this[ a[ i ] ] = o[ a[ i ] ];
        } catch( e ) {}
    }
}


/* event methods */

if( !defined( window.Event ) )
    Event = {};


Event.stop = function( ev ) {
    ev = ev || this;
    if( ev === Event )
        ev = window.event;

    // w3c
    if( ev.preventDefault )
        ev.preventDefault();
    if( ev.stopPropagation )
        ev.stopPropagation();

    // ie
    try {
        ev.cancelBubble = true;
        ev.returnValue = false;
    } catch( e ) {}

    return false;
}


Event.prep = function( ev ) {
    ev = ev || window.event;
    if( !defined( ev.stop ) )
        ev.stop = this.stop;
    if( !defined( ev.target ) )
        ev.target = ev.srcElement;
    if( !defined( ev.relatedTarget ) ) 
        ev.relatedTarget = ev.toElement;
    return ev;
}


try { Event.prototype.stop = Event.stop; }
catch( e ) {}


/* object extensions */

Function.stub = function() {};


/* function extensions */

extend( Function.prototype, {
    bind: function( o ) {
        var m = this;
        return function() {
            return m.apply( o, arguments );
        };
    },
    
    
    bindEventListener: function( o ) {
        var m = this; // Use double closure to work around IE 6 memory leak.
        return function( e ) {
            try {
                event = Event.prep( e );
            } catch( e ) {}
            return m.call( o, e );
        };
    },
    
    
    applySuper: function( o, args ) {
        return this.__super.apply( o, args || [] );
    },
    
    
    callSuper: function( o ) {
        var args = [];
        for( var i = 1; i < arguments.length; i++ )
            args.push( arguments[ i ] );
        return this.__super.apply( o, args );
    }
} );


/* class helpers */

indirectObjects = [];
__SUBCLASS__ = { __SUBCLASS__: "__SUBCLASS__" };


Class = function( sc ) {
    var c = function( s ) {
        this.constructor = arguments.callee;
        if( s === __SUBCLASS__ )
            return;
        this.init.apply( this, arguments );
    };
    
    override( c, Class );
    sc = sc || Object;
    override( c, sc );
    c.__super = sc;
    c.superClass = sc.prototype;
    
    c.prototype = sc === Object ? new sc() : new sc( __SUBCLASS__ );
    extend( c.prototype, Class.prototype );
    var a = arguments;
    for( var i = 1; i < a.length; i++ )
        override( c.prototype, a[ i ] );
    c.prototype.constructor = sc; /* the above override could blow this away */
    
    for( var p in c.prototype ) {
        var m = c.prototype[ p ];
        if( typeof m != "function" || defined( m.__super ) )
            continue;
        m.__super = null;
        var pr = c.prototype;
        while( pr && !m.__super ) {
            if( pr === pr.constructor.prototype )
                break;
            pr = pr.constructor.prototype;
            var s = pr[ p ];
            if( defined( s ) && typeof s == "function" )
                m.__super = s;
        }
    }
    
    return c;
}


extend( Class, {
    initSingleton: function() {
        if( this.singleton )
            return this.singleton;
        var c = this.singletonConstructor || this;
        this.singleton = new c();
        return this.singleton;
    }
} );


Class.prototype = {
    init: function() {},
    
    
    destroy: function() {
        try {
            if( this.indirectIndex )
                indirectObjects[ this.indirectIndex ] = undefined;
            delete this.indirectIndex;
        } catch( e ) {}
        
        for( var property in this ) {
            try {
                if( this.hasOwnProperty( property ) )
                    delete this[ property ];
            } catch( e ) {}
        }
    },
    
    
    getBoundMethod: function( mn ) {
        return this[ mn ].bind( this );
    },
    
    
    getEventListener: function( mn ) {
        return this[ mn ].bindEventListener( this );
    },
    
    
    getIndirectIndex: function() {
        if( !defined( this.indirectIndex ) ) {
            this.indirectIndex = indirectObjects.length;
            indirectObjects.push( this );
        }
        return this.indirectIndex;
    },
    
    
    getIndirectMethod: function( mn ) {
        if( !this.indirectMethods )
            this.indirectMethods = {};
        var m = this[ mn ];
        if( typeof m != "function" )
            return undefined;
        var indirectIndex = this.getIndirectIndex();
        if( !this.indirectMethods[ mn ] ) {
            this.indirectMethods[ mn ] = new Function(
                "var o = indirectObjects[" + indirectIndex + "];" +
                "return o['" + mn + "'].apply( o, arguments );"
            );
        }
        return this.indirectMethods[ mn ];
    },
    
    
    getIndirectEventListener: function( mn ) {
        if( !this.indirectEventListeners )
            this.indirectEventListeners = {};
        var m = this[ mn ];
        if( typeof m != "function" )
            return undefined;
        var indirectIndex = this.getIndirectIndex();
        if( !this.indirectEventListeners[ mn ] ) {
            this.indirectEventListeners[ mn ] = new Function( "event",
                "try { event = Event.prep( event ); } catch( e ) {}" +
                "var o = indirectObjects[" + indirectIndex + "];" +
                "return o." + mn + ".call( o, event );"
            );
        }
        return this.indirectEventListeners[ mn ];
    }
}


/* string extensions */

extend( String, {
    escapeJSChar: function( c ) {
        // try simple escaping
        switch( c ) {
            case "\\": return "\\\\";
            case "\"": return "\\\"";
            case "'":  return "\\'";
            case "\b": return "\\b";
            case "\f": return "\\f";
            case "\n": return "\\n";
            case "\r": return "\\r";
            case "\t": return "\\t";
        }
        
        // return raw bytes now ... should be UTF-8
        if( c >= " " )
            return c;
        
        // try \uXXXX escaping, but shouldn't make it for case 1, 2
        c = c.charCodeAt( 0 ).toString( 16 );
        switch( c.length ) {
            case 1: return "\\u000" + c;
            case 2: return "\\u00" + c;
            case 3: return "\\u0" + c;
            case 4: return "\\u" + c;
        }
        
        // should never make it here
        return "";
    },
    
    
    encodeEntity: function( c ) {
        switch( c ) {
            case "<": return "&lt;";
            case ">": return "&gt;";
            case "&": return "&amp;";
            case '"': return "&quot;";
            case "'": return "&apos;";
        }
        return c;
    },


    decodeEntity: function( c ) {
        switch( c ) {
            case "amp": return "&";
            case "quot": return '"';
            case "apos": return "'";
            case "gt": return ">";
            case "lt": return "<";
        }
        var m = c.match( /^#(\d+)$/ );
        if( m && defined( m[ 1 ] ) )
            return String.fromCharCode( m[ 1 ] );
        m = c.match( /^#x([0-9a-f]+)$/i );
        if(  m && defined( m[ 1 ] ) )
            return String.fromCharCode( parseInt( hex, m[ 1 ] ) );
        return c;
    }
} );


extend( String.prototype, {
    escapeJS: function() {
        return this.replace( /([^ -!#-\[\]-~])/g, function( m, c ) { return String.escapeJSChar( c ); } )
    },
    
    
    escapeJS2: function() {
        return this.replace( /([\u0000-\u0031'"\\])/g, function( m, c ) { return String.escapeJSChar( c ); } )
    },
    
    
    escapeJS3: function() {
        return this.replace( /[\u0000-\u0031'"\\]/g, function( m ) { return String.escapeJSChar( m ); } )
    },
    
    
    escapeJS4: function() {
        return this.replace( /./g, function( m ) { return String.escapeJSChar( m ); } )
    },
    
    
    encodeHTML: function() {
        return this.replace( /([<>&"])/g, function( m, c ) { return String.encodeEntity( c ) } ); /* fix syntax highlight: " */
    },


    decodeHTML: function() {
        return this.replace( /&(.*?);/g, function( m, c ) { return String.decodeEntity( c ) } );
    },
    
    
    cssToJS: function() {
        return this.replace( /-([a-z])/g, function( m, c ) { return c.toUpperCase() } );
    },
    
    
    jsToCSS: function() {
        return this.replace( /([A-Z])/g, function( m, c ) { return "-" + c.toLowerCase() } );
    },
    
    
    firstToLowerCase: function() {
        return this.replace( /^(.)/, function( m, c ) { return c.toLowerCase() } );
    },
    
        
    rgbToHex: function() {
        var c = this.match( /(\d+)\D+(\d+)\D+(\d+)/ );
        if( !c )
            return undefined;
        return "#" +
            finiteInt( c[ 1 ] ).toString( 16 ).pad( 2, "0" ) +
            finiteInt( c[ 2 ] ).toString( 16 ).pad( 2, "0" ) +
            finiteInt( c[ 3 ] ).toString( 16 ).pad( 2, "0" );
    },
    
    
    pad: function( length, padChar ) {
        var padding = length - this.length;
        if( padding <= 0 )
            return this;
        if( !defined( padChar ) )
            padChar = " ";
        var out = [];
        for( var i = 0; i < padding; i++ )
            out.push( padChar );
        out.push( this );
        return out.join( "" );
    },


    trim: function() {
        return this.replace( /^\s+|\s+$/g, "" );
    },
    
    
    interpolate: function( vars ) {
        return this.replace( /(?!\\)\$\{([^\}]+)\}|(?!\\)\$([a-zA-Z][a-zA-Z0-9_]*)|\\\$/g,
            function( m, a, b ) {
                with( vars ) {
                    if( a )
                        return eval( "(" + a + ")" );
                    else if( b )
                        return eval( "(" + b + ")" );
                }
                return "$";
            } );
    }
} );


/* extend array object */

extend( Array, { 
    fromPseudo: function () {
        var out = [];
        for ( var j = 0; j < arguments.length; j++ )
            for ( var i = 0; i < arguments[ j ].length; i++ )
                out.push( arguments[ j ][ i ] );
        return out;
    }
});


/* extend array object */

extend( Array.prototype, {
    copy: function() {
        var out = [];
        for( var i = 0; i < this.length; i++ )
            out[ i ] = this[ i ];
        return out;
    },


    first: function( c, o ) {
        var l = this.length;
        for( var i = 0; i < l; i++ ) {
            var result = o
                ? c.call( o, this[ i ], i, this )
                : c( this[ i ], i, this );
            if( result )
                return this[ i ];
        }
        return null;
    },


    fitIndex: function( i, di ) {
        if( !exists( i ) )
            i = di;
        else if( i < 0 ) {
            i = this.length + i;
            if( i < 0 )
                i = 0;
        } else if( i >= this.length )
            i = this.length - 1;
        return i;
    },


    scramble: function() {
        for( var i = 0; i < this.length; i++ ) {
            var j = Math.floor( Math.random() * this.length );
            var t = this[ i ];
            this[ i ] = this[ j ];
            this[ j ] = t;
        }
    },
    
    
    add: function() {
        var a = arguments;
        for( var i = 0; i < a.length; i++ ) {
            var j = this.indexOf( a[ i ] );
            if( j < 0 ) 
                this.push( arguments[ i ] );
        }
        return this.length;
    },
        
    
    remove: function() {
        var a = arguments;
        for( var i = 0; i < a.length; i++ ) {
            var j = this.indexOf( a[ i ] );
            if( j >= 0 )
                this.splice( j, 1 );
        }
        return this.length;
    },


    /* javascript 1.5 array methods */
    /* http://developer-test.mozilla.org/en/docs/Core_JavaScript_1.5_Reference:Objects:Array#Methods */

    every: function( c, o ) {
        var l = this.length;
        for( var i = 0; i < l; i++ )
            if( !(o ? c.call( o, this[ i ], i, this ) : c( this[ i ], i, this )) )
                return false;
        return true;
    },


    some: function( c, o ) {
        var l = this.length;
        for( var i = 0; i < l; i++ )
            if( o ? c.call( o, this[ i ], i, this ) : c( this[ i ], i, this ) )
                return true;
        return false;
    },


    filter: function( c, o ) {
        var out = [];
        var l = this.length;
        for( var i = 0; i < l; i++ )
            if( o ? c.call( o, this[ i ], i, this ) : c( this[ i ], i, this ) )
                out.push( this[ i ] );
        return out;
    },
    
    
    forEach: function( c, o ) {
        var l = this.length;
        for( var i = 0; i < l; i++ )
            o ? c.call( o, this[ i ], i, this ) : c( this[ i ], i, this );
    },
    
    
    indexOf: function( v, fi ) {
        fi = this.fitIndex( fi, 0 );
        for( var i = 0; i < this.length; i++ )
            if( this[ i ] === v )
                return i;
        return -1;
    },


    lastIndexOf: function( v, fi ) {
        fi = this.fitIndex( fi, this.length - 1 );
        for( var i = fi; i >= 0; i-- )
            if( this[ i ] == v )
                return i;
        return -1;
    },


    /* javascript 1.2 array methods */

    concat: function() {
        var a = arguments;
        var o = this.copy();
        for( i = 0; i < a.length; i++ ) {
            var b = a[ i ];
            for( j = 0; j < b.length; j++ )
                o.push( b[ j ] );
        }
        return o;
    },
    

    push: function() {
        var a = arguments;
        for( var i = 0; i < a.length; i++ )
            this[ this.length ] = a[ i ];
        return this.length;     
    },


    pop: function() {
        if( this.length == 0 )
            return undefined;
        var o = this[ this.length - 1 ];
        this.length--;
        return o;
    },
    
    
    unshift: function() {
        var a = arguments;
        for( var i = 0; i < a.length; i++ ) {
            this[ i + a.length ] = this[ i ];
            this[ i ] = a[ i ];
        }
        return this.length;     
    },
    
    
    shift: function() {
        if( this.length == 0 )
            return undefined;
        var o = this[ 0 ];
        for( var i = 1; i < this.length; i++ )
            this[ i - 1 ] = this[ i ];
        this.length--;
        return o;
    }
} );


/* date extensions */

extend( Date, {
    strings: {
        localeWeekdays: {},
        localeShortWeekdays: {},
        localeMonths: {},
        localeShortMonths: {}
    },
    
    
    /*  iso 8601 date format parser
        this was fun to write...
        thanks to: http://www.cl.cam.ac.uk/~mgk25/iso-time.html */

    matchISOString: new RegExp(
        "^([0-9]{4})" +                                                     // year
        "(?:-(?=0[1-9]|1[0-2])|$)(..)?" +                                   // month
        "(?:-(?=0[1-9]|[12][0-9]|3[01])|$)([0-9]{2})?" +                    // day of the month
        "(?:T(?=[01][0-9]|2[0-4])|$)T?([0-9]{2})?" +                        // hours
        "(?::(?=[0-5][0-9])|\\+|-|Z|$)([0-9]{2})?" +                        // minutes
        "(?::(?=[0-5][0-9]|60$|60[+|-|Z]|60.0+)|\\+|-|Z|$):?([0-9]{2})?" +  // seconds
        "(\\.[0-9]+)?" +                                                    // fractional seconds
        "(Z|\\+[01][0-9]|\\+2[0-4]|-[01][0-9]|-2[0-4])?" +                  // timezone hours
        ":?([0-5][0-9]|60)?$"                                               // timezone minutes
    ),
    
    
    fromISOString: function( string ) {
        var t = this.matchISOString.exec( string );
        if( !t )
            return undefined;

        var y = finiteInt( t[ 1 ], 10 );
        var mo = finiteInt( t[ 2 ], 10 ) - 1;
        var d = finiteInt( t[ 3 ], 10 );
        var h = finiteInt( t[ 4 ], 10 );
        var m = finiteInt( t[ 5 ], 10 );
        var s = finiteInt( t[ 6 ], 10 );
        var ms = finiteInt( Math.round( parseFloat( t[ 7 ] ) * 1000 ) );
        var tzh = finiteInt( t[ 8 ], 10 );
        var tzm = finiteInt( t[ 9 ], 10 );

        var date = new this( 0 );
        if( defined( t[ 8 ] ) ) {
            date.setUTCFullYear( y, mo, d );
            date.setUTCHours( h, m, s, ms );
            var o = (tzh * 60 + tzm) * 60000;
            if( o )
                date = new this( date - o );
        } else {
            date.setFullYear( y, mo, d );
            date.setHours( h, m, s, ms );
        }

        return date;
    }
} );


extend( Date.prototype, {
    clone: function () {
        return new Date( this.getTime() );
    },


    getISOTimezoneOffset: function() {
        var o = -this.getTimezoneOffset();
        var n = 0;
        if( o < 0 ) {
            n = 1;
            o *= -1;
        }
        var h = Math.floor( o / 60 ).toString().pad( 2, "0" );
        var m = Math.floor( o % 60 ).toString().pad( 2, "0" );
        return (n ? "-" : "+") + h + ":" + m;
    },


    toISODateString: function() {
        var y = this.getFullYear();
        var m = (this.getMonth() + 1).toString().pad( 2, "0" );
        var d = this.getDate().toString().pad( 2, "0" );
        return y + "-" + m + "-" + d;
    },


    toUTCISODateString: function() {
        var y = this.getUTCFullYear();
        var m = (this.getUTCMonth() + 1).toString().pad( 2, "0" );
        var d = this.getUTCDate().toString().pad( 2, "0" );
        return y + "-" + m + "-" + d;
    },


    toISOTimeString: function() {
        var h = this.getHours().toString().pad( 2, "0" );
        var m = this.getMinutes().toString().pad( 2, "0" );
        var s = this.getSeconds().toString().pad( 2, "0" );
        var ms = this.getMilliseconds().toString().pad( 3, "0" );
        var tz = this.getISOTimezoneOffset();
        return h + ":" + m + ":" + s + "." + ms + tz;
    },


    toUTCISOTimeString: function() {
        var h = this.getUTCHours().toString().pad( 2, "0" );
        var m = this.getUTCMinutes().toString().pad( 2, "0" );
        var s = this.getUTCSeconds().toString().pad( 2, "0" );
        var ms = this.getUTCMilliseconds().toString().pad( 3, "0" );
        return h + ":" + m + ":" + s + "." + ms + "Z";
    },


    toISOString: function() {
        return this.toISODateString() + "T" + this.toISOTimeString();
    },


    toUTCISOString: function() {
        return this.toUTCISODateString() + "T" + this.toUTCISOTimeString();
    },

    
    /* day of week, not day of month */
    getLocaleDayString: function( d ) {
        if( isNaN(d) )
            d = this.getDay();
        return this.constructor.strings.localeWeekdays[ d ];
    },
    

    /* day of week, not day of month */
    getLocaleDayShortString: function( d ) {
        if( isNaN(d) )
            d = this.getDay();
        return this.constructor.strings.localeShortWeekdays[ d ];
    },


    getLocaleMonthString: function( m ) {
        if( isNaN(m) )
            m = this.getMonth();
        return this.constructor.strings.localeMonths[ m ];
    },


    getLocaleMonthShortString: function( m ) {
        if( isNaN(m) )
            m = this.getMonth();
        return this.constructor.strings.localeShortMonths[ m ];
    }
} );


/* ajax */

if( !defined( window.XMLHttpRequest ) ) {
    window.XMLHttpRequest = function() {
        var h = [
            "Microsoft.XMLHTTP",
            "MSXML2.XMLHTTP.5.0",
            "MSXML2.XMLHTTP.4.0",
            "MSXML2.XMLHTTP.3.0",
            "MSXML2.XMLHTTP"
        ];
        
        for( var i = 0; i < h.length; i++ ) {
            try {
                return new ActiveXObject( h[ i ] );
            } catch( e ) {}
        }
        
        return undefined;
    }
}
