/*
JavasSript Object Notation (JSON) - Copyright 2005 Six Apart
$Id$

Copyright (c) 2005-2006, Six Apart, Ltd.
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


JSON = {
    parse: function( string ) {
        string = string.replace( /=/g, "\\u3D" );
        string = string.replace( /\(/g, "\\u28" );
        try {
            return eval( "(" + string + ")" );
        } catch( e ) {}
        return undefined;
    }
}


Boolean.prototype.toJSON = function() {
    return this.toString();
}


Number.prototype.toJSON = function() {
    return isFinite( this ) ? this.toString() : "0";
}


Date.prototype.toJSON = function() {
    return this.toUTCISOString
        ? this.toUTCISOString().toJSON()
        : this.toString().toJSON();
}


String.prototype.toJSON = function() {
    return '"' + this.escapeJS() + '"';
}


RegExp.prototype.toJSON = function() {
    return this.toString().toJSON();
}


Function.prototype.toJSON = function() {
    return this.toString().toJSON();
}


Array.prototype.toJSON = function( root ) {
    // crude recursion detection
    if( !root )
        root = this;
    else if( root == this )
        return "[]";
    
    var out = [ "[" ];
    for( var i = 0; i < this.length; i++ ) {
        if( out.length > 1 )
            out.push( "," );
        if( typeof this[ i ] == "undefined" || this[ i ] == null )
            out.push( "null" );
        else if( !this[ i ].toJSON )
            out.push( "{}" );
        else
            out.push( this[ i ].toJSON( root ) );
    }
    out.push( "]" );
    return out.join( "" );
}


Object.prototype.toJSON = function( root ) {
    // crude recursion detection
    if( !root )
        root = this;
    else if( root == this )
        return "{}";
    
    var out = [ "{" ];
    for( var i in this ) {
        if( typeof this[ i ] == "undefined" ||
            (this.hasOwnProperty && !this.hasOwnProperty( i )) )
            continue;
        if( out.length > 1 )
            out.push( "," );
        out.push( i.toJSON() );
        if( this[ i ] == null )
            out.push( ":null" );
        else if( typeof this[ i ] == "function" )
            continue;
        else if( !this[ i ].toJSON )
            out.push( ":{}" );
        else
            out.push( ":", this[ i ].toJSON( root ) );
    }
    out.push( "}" );
    return out.join( "" );
}
