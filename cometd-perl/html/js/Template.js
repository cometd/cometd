/*
Template - Copyright 2005 Six Apart
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


/* core template object */

Template = new Class( Object, {
    beginToken: "[#",
    endToken: "#]",
    
    
    init: function( source ) {
        if( source )
            this.compile( source );
    },
    
    
    compile: function( source ) {
        var statements = [
            "context.open();",
            "with( context.vars ) { "
        ];
        
        var start = 0, end = -this.endToken.length;
        while( start < source.length ) {
            end += this.endToken.length;
            
            /* plaintext */
            start = source.indexOf( this.beginToken, end );
            
            if( start < 0 )
                start = source.length;
            if( start > end )
                statements.push( "context.write( ", '"' + source.substring( end, start ).escapeJS() + '"', " );" );
            
            start += this.beginToken.length;
            
            // code
            if( start >= source.length )
                break;
                
            end = source.indexOf( this.endToken, start );
            
            if( end < 0 )
                throw "Template parsing error: Unable to find matching end token (" + this.endToken + ").";
                
            var length = ( end - start );
            
            /* empty tag */
            if( length <= 0 )
                continue;
            
            /* comment */
            else if( length >= 4 &&
                source.charAt( start ) == "-" &&
                source.charAt( start + 1 ) == "-" &&
                source.charAt( end - 1 ) == "-" &&
                source.charAt( end - 2 ) == "-" )
                continue;
            
            /* write */
            else if( source.charAt( start ) == "=" )
                statements.push( "context.write( ", source.substring( start + 1, end ), " );" );
            
            else if( source.charAt( start ) == "*" ) {
                // commands that effect flow
                
                var cmd = source.substring( start + 1, end ).match( /^(\w+)/ );
                if ( cmd ) {
                    cmd = cmd[ 1 ];
                
                    switch ( cmd ) {
                        case "return":
                            statements.push( "return context.close();" );
                    }
                }
            
            /* filters */
            } else if( source.charAt( start ) == "|" ) {
                start += 1;

                /* find the first whitespace */
                var afterfilters = source.substring( start, end ).search(/\s/);
                
                var filters = [];
                var params = [];
                if (afterfilters > 0) {
                    /* pipes or commas must seperate filters 
                     * split the string, reverse and rejoin to reverse it
                     */
                    filters = source.substring( start,start + afterfilters ).replace(/(\w+)(\(([^\)]+)\))?/g,"$1|$3").split( "|" );
                    
                    afterfilters += 1; /* data starts after whitespace and filter list */
                } else {
                    /* default to escapeHTML */
                    filters = [ "h", "" ];
                }

                var cmds = [];
                var params = [];
                for ( var j = 0; j < filters.length; j++ ) {
                    if (j % 2)
                        params.push( filters[ j ] );
                    else
                        cmds.push( filters[ j ] );
                }
                
                /* we have to do them in reverse order */
                filters = cmds.reverse();
               
                /* start with our original filter number */
                var numfilters = filters.length;
                
                /* add the text between [#|  #] */
                filters.push( source.substring( start + afterfilters, end ) );
                
                /* adjust each filter into a function call */
                /* H|substr(-1,1)|u */
                /* eg. u( substr( H( name ), -1, 1 ) ) */
                for ( var i = 0; i < numfilters; i++ ) {
                    filters[ i ] = " context.f." + filters[ i ] + "( ";
                    filters.push( ", context" );
                    if ( params[ i ] != "" )
                        filters.push( ", [" + params[ i ] + "]" );
                    filters.push( " )" );
                }

                /* rewrite command params */
                filters = filters.join( "" );
                statements.push( "context.write( " + filters + " );");
            }
            
            /* evaluate */
            else
                statements.push( source.substring( start, end ) );
        }
        
        statements.push( "} return context.close();" );
        this.process = new Function( "context", statements.join( "\n" ) );
    },
    
    
    process: function( context ) {
        return "";
    },
    
    
    /* deprecated */
    
    exec: function( context ) {
        log( "Template::exec() method has been deprecated. Please use process() instead or " +
            "the new static Template.process( name[, vars[, templates]] ) method." );
        return this.process( context );
    }
    
} );


/* static members */

extend( Template, {
    templates: {},
    
    
    process: function( name, vars, templates ) {
        var context = new Template.Context( vars, templates );
        return context.include( name );
    }
} );


/* context object */

Template.Context = new Class( Object, {
    init: function( vars, templates ) {
        this.vars = vars || {};
        this.templates = templates || Template.templates;
        this.stack = [];
        this.out = [];
        this.f = Template.Filter;
    },
    
    
    include: function( name ) {
        if ( !this.templates.hasOwnProperty( name ) ) {
            log.error( "Template name " + name + " does not exist!" );
            return;
        }
        
        if ( typeof this.templates[ name ] == "string" )
            this.templates[ name ] = new Template( this.templates[ name ] );
        try {
            return this.templates[ name ].process( this );
        } catch( e ) {
            var error = "Error while processing template:" + name + " - " + e.message;
            log.error( error );
            throw error;
        }
    },


    write: function() {
        this.out.push.apply( this.out, arguments );
    },


    writeln: function() {
        this.write.apply( this, arguments );
        this.write( "\n" );
    },

    
    clear: function() {
        this.out.length = 0;
    },


    exit: function() {
        return this.getOutput();
    },
    

    getOutput: function() {
        return this.out.join( "" );
    },
    
    
    open: function() {
        this.stack.push( this.out );
        this.out = [];
    },
    
    
    close: function() {
        var result = this.getOutput();
        this.out = this.stack.pop() || [];
        return result;
    }
} );


/* filters */

Template.Filter = {
    /* interpolate */
    i: function( string, context ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
        return string.interpolate( context.vars );
    },
    
    
    /* escapeHTML */
    h: function( string, context ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
        return ( typeof string == "string" )
            ? string.encodeHTML() : "".encodeHTML( string );
    },


    /* unescapeHTML */
    H: function( string, context ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
        return ( typeof string == "string" )
            ? string.decodeHTML() : "".encodeHTML( string );
    },


    /* decodeURI */
    U: function( string, context ) {
        return decodeURI( string );
    },


    /* escapeURI */
    u: function( string, context ) {
        return encodeURI( string ).replace( /\//g, "%2F" );
    },


    /* lowercase */
    lc: function( string, context ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
        return ( typeof string == "string" ) 
            ? string.toLowerCase() : "".toLowerCase( string );
    },


    /* uppercase */
    uc: function( string, context ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
        return ( typeof string == "string" )
            ? string.toUpperCase() : "".toUpperCase( string );
    },
    
    
    /* substr */
    substr: function( string, context, params ) {
        if( !params )
            throw "Template Filter Error: substr() requires at least one parameter";
        
        /* allow negative offset */
        if( params[ 0 ] < 0 )
            params[ 0 ] = string.length + params[ 0 ];
        
        return String.substr.apply( string, params );
    },


    /* removes whitepace before and after */
    ws: function( string, context ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
        return ( typeof string == "string" )
            ? string.replace( /^\s+/g, "" ).replace( /\s+$/g, "" ) : string;
    },
    
    
    /* trims to length and adds elipsis */
    trim: function( string, context, params ) {
        if ( !params )
            throw "Template Filter Error: trim() requires at least one parameter";
       
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
       
        if ( ( typeof string == "string" ) && string.length > params[ 0 ] ) {
            string = string.substr( 0, params[ 0 ] );
            /* don't trunc on a word */
            var newstr = string.replace( /\w+$/, "" );
            return ( ( newstr == "" ) ? string : newstr ) + "\u2026";
        } else
            return string;
    },


    /* returns YYYY-MM-DD from an iso string like: 1995-02-05T13:00:00.000-08:00 */
    date: function( string, context ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
        var date = Date.fromISOString( string );
        return ( date ) ? date.toISODateString() : "";
    },


    localeDate: function( string, context ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
        var date = Date.fromISOString( string );
        return ( date ) ? date.toLocaleString() : "";
    },


    /* remove html tags */
    rt: function( string, context ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();
        return ( typeof string == "string" )
            ? string.replace( /<\/?[^>]+>/gi, "" ) : string;
    },


    rp: function( string, params ) {
        if ( ( typeof string != "string" ) && string && string.toString )
            string = string.toString();

        if ( ( typeof string == "string" ) && params.length == 2 ) {
            return string.replace( params[ 0 ], params[ 1 ] );
        } else
            return string;
    }
};
