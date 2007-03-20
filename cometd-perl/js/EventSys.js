/* CopyRight (c) 2007 David Davis
 * See LICENSE file
 */

EventSubscribers = [ ];

EventDispatcher = new Class( Object, {


    initObject: function() {
        if ( !this.subscriber )
            return;
        
        EventSubscribers.add( this );
    },


    deliverEvents: function( ev ) {
        for ( var i = 0; i < ev.length; i++ ) {
            if ( !ev[ i ].hasOwnProperty( "channel" ) )
                continue;
            for ( var j = 0; j < EventSubscribers.length; j++ ) {
                var m = EventSubscribers[ j ][ ev[ i ].channel ];
                if ( typeof m != "function" )
                    continue;
                var method = EventSubscribers[ j ].getIndirectMethod( ev[ i ].channel );
                method.call( method, ev[ i ] );
            }
        }
    }


} );
