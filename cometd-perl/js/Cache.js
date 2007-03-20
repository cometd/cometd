/*
LRU Cache Library
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

Cache = new Class( Object, {
    maxLength: 100,
    hits: 0,
    misses: 0,
    
    
    init: function( maxLength ) {
        if ( maxLength > 0 )
            this.maxLength = maxLength;
        this.flush();
    },


    /* public interface */

    /* flush the cache */
    flush: function() {
        /* xxx should this clear the hits and miss? */
        
        this.length = 0;
        
        /* least recently used */
        this.LRU = 0;
        
        /* most recently used */
        this.MRU = 0;
        
        /* idx to position in value,prev,next arrays */
        this.IDX = {};
        
        /* each node has the same offset in: */
        this.KEY = [];
        this.VALUE = [];
        this.PREV = [];
        this.NEXT = [];
        this.DELETE = [];
    },


    getItemsOrdered: function( offset, count ) {
        offset = offset || 0;
        count = count || this.length;

        var keys = [];
        var values = [];
        
        var idx = this.MRU;
        var c = 0;
        for( var i = 0; i < this.length; i++ ) {
            /* walk until it reaches the needed offset */
            if ( i < offset ) {
                idx = this.NEXT[ idx ];
                continue;
            }
            
            keys.push( this.KEY[ idx ] );
            values.push( this.VALUE[ idx ] );
            
            c++;
            /* keep pulling keys/values until count is reached, or end of array */
            if ( c >= count )
                break;
            
            idx = this.NEXT[ idx ];
        }

        return [ keys, values ];
    },


    getItems_: function() {
        /* xxx does not account for order AND undef values due to deletion */
        /* use getItemsOrdered if that behavior is needed */
        return [ this.KEY, this.VALUE ];
    },


    deleteItem: function( key ) {
        if ( !this.IDX.hasOwnProperty( key ) )
            return undefined;
        var value = this.VALUE[ this.IDX[ key ] ];
        this.deleteNode( key );
        return value;
    },


    setItem: function( key, value) {
        if ( !defined( key ) || !defined( value ) )
            return undefined;
       
        var idx;
        if ( this.IDX.hasOwnProperty( key ) )
            idx = this.deleteNode( key );
        else if ( this.length >= this.maxLength && defined( this.KEY[ this.LRU ] ) )
            idx = this.deleteNode( this.KEY[ this.LRU ] );
        else if ( this.KEY.length && this.KEY[ this.LRU ] == undefined )
            idx = this.LRU;
            
        this.insertNode( key, value, idx );
        return value;
    }, 
 
 
    getItem: function( key ) {
        /* index of node requested */
        var idx = this.IDX[ key ];
            
        /* does this node exist */
        if ( !this.IDX.hasOwnProperty( key ) || idx < 0 || idx >= this.length || !defined( this.VALUE[ idx ] ) ) {
            /* cache miss stat */
            this.misses++;
            return undefined;
        }
        
        /* cache hit stat */
        this.hits++;

        /* move it to the front */
        this.setMRU( idx );
        
        return this.VALUE[ idx ];
    },


    getItems: function( ids ) {
        
        var items = [];
        for ( var i = 0; i < ids.length; i++ ) {
            var item = this.getItem( ids[ i ] );
            if ( item )
                items.push( item );
        }
        
        return items;
    },


    touchItem: function( key ) {
        /* index of node requested */
        var idx = this.IDX[ key ];
            
        /* does this node exist */
        if ( !this.IDX.hasOwnProperty(key) || idx < 0 || idx >= this.length || !defined( this.VALUE[ idx ] ) )
            return undefined;
        
        /* move it to the front */
        this.setMRU( idx );
    },


    /* private functions */

    setMRU: function( idx ) {
        var prevnode = this.PREV[ idx ];
        var nextnode = this.NEXT[ idx ];
        
        if (prevnode == -1) {
            /* this can happen if you select the mru */
            if (this.MRU != idx)
                log.error("LRUCache::setMRU idx:" + idx + " has an inconsistent PREV key (PREV:-1 but MRU != idx)");
            return;
        }
        
        this.connectNodes( prevnode, nextnode );
            
        /* make this node the mru by making the current mru a peer of this node */
        this.PREV[ this.MRU ] = idx;
        this.NEXT[ idx ] = this.MRU;
        
        this.PREV[ idx ] = -1;
        this.MRU = idx;
    },


    setLRU: function( idx ) {
        var nextnode = this.NEXT[ idx ];
        var prevnode = this.PREV[ idx ];
        
        if (nextnode == -1) {
            if (this.LRU != idx)
                log.error("LRUCache::setLRU  idx:" + idx + " has an inconsistent NEXT key (NEXT:-1 but LRU != idx)");
            return;
        }
        
        this.connectNodes( prevnode, nextnode );
        
        /* move it to the end */
        this.NEXT[ this.LRU ] = idx;
        this.PREV[ idx ] = this.LRU;
        
        this.NEXT[ idx ] = -1;
        this.LRU = idx;
    },


    connectNodes: function( prevnode, nextnode ) {
        /* match peers to each other */
        
        if (prevnode == -1)
            this.MRU = nextnode;
        else
            this.NEXT[ prevnode ] = nextnode;
        
        
        if (nextnode == -1)
            this.LRU = prevnode;
        else
            this.PREV[ nextnode ] = prevnode;

    },


    /* reposition a nodes peers and return the index */  
    deleteNode: function( key ) {
        var idx = this.IDX[ key ];
        
        /* move it to the end */
        this.setLRU( idx );
        
        delete this.IDX[ key ];
        
        this.KEY[ idx ] = undefined;
        this.VALUE[ idx ] = undefined;
        
        /* the node isnt actually deleted, it is reused */
        return idx;
    },

 
    insertNode: function( key, value, idx ) {
        /* insert new node */
        if ( !defined( idx ) )
            idx = this.length++;
        
        /* move it to the front */
        this.setMRU( idx );
        
        this.VALUE[ idx ] = value;
        this.KEY[ idx ] = key;
        this.IDX[ key ] = idx;

        return idx;
    },


    /* for debugging */
    visualize: function() {
        var c = this.LRU;
        log.warn( "LRUCache::visualize MRU: " + c );
        for( var i = 0; i < this.length; i++ ) {
            log.warn( "LRUCache::visualize [ " + this.KEY[ c ] + " ] " + c +
                ((this.LRU == c) ? " - LRU" : "") + ((this.MRU == c) ? " - MRU" : ""));
            c = this.PREV[ c ];
        }
    }
});
