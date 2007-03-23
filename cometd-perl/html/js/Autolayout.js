/*
DOM Autolayout Interface
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

Autolayout = {
    matchAutolayout: /(?:^|\s)autolayout-(\S+)(?:\s|$)/,
    
    
    matchSingleAutolayout: /^autolayout-(\S+)$/,
    
    
    applyAutolayouts: function( e ) {
        var cs = DOM.getClassNames( e );
        for( var i = 0; i < cs.length; i++ ) {
            var r = this.matchSingleAutolayout.exec( cs[ i ] );
            if( !r || !r[ 1 ] )
                continue;
            var l = r[ 1 ].cssToJS();
            if( !this.layouts.hasOwnProperty( l ) )
                continue;
            this.layouts[ l ].apply( this, arguments );
        }
    }
}


Autolayout.layouts = {
    center: function( e ) {
        DOM.setLeft( e, finiteInt( e.parentNode.clientWidth / 2 ) - finiteInt( e.offsetWidth / 2 ) );
        DOM.setTop( e, finiteInt( e.parentNode.clientHeight / 2 ) - finiteInt( e.offsetHeight / 2 ) );
    },
    
    
    heightParent: function( e ) {
        DOM.setHeight( e, finiteInt( e.parentNode.clientHeight ) );
    },
    
    
    heightNext: function( e ) {
        var ne = DOM.getNextElement( e );
        if( !ne )
            return;
        DOM.setHeight( e, finiteInt( ne.offsetTop ) - finiteInt( e.offsetTop ) );
    },
    
    
    flyout: function( e ) {
        var d = DOM.getIframeAbsoluteDimensions( this.targetElement );
        if( !d )
            return;
        DOM.setLeft( e, d.absoluteLeft );
        DOM.setTop( e, d.absoluteBottom );
    },
    
    
    FLYOUT_SMART_EPSILON_Y: 200,
    
    flyoutSmart: function( e ) {
        var td = DOM.getIframeAbsoluteDimensions( this.targetElement );
        if( !td )
            return;
        var dd = DOM.getDocumentDimensions( DOM.getOwnerDocument( e ) );
        
        if( td.absoluteLeft > (dd.width / 2) ) {
            e.style.left = "auto";
            DOM.setRight( e, (dd.width - td.absoluteRight) );
        } else {
            e.style.right = "auto";
            DOM.setLeft( e, td.absoluteLeft );
        }
        
        /* 300px seems like a good fudge epsilon */
        if( td.absoluteTop > (dd.height - 300) ) {
            e.style.top = "auto";
            DOM.setBottom( e, (dd.height - td.absoluteBottom) );
        } else {
            e.style.bottom = "auto";
            DOM.setTop( e, td.absoluteTop );
        }
    },


    flyoutUp: function( e ) {
        var d = DOM.getIframeAbsoluteDimensions( this.targetElement );
        if( !d )
            return;

        var a = DOM.getDimensions( e );
        DOM.setLeft( e, d.absoluteLeft );
        DOM.setTop( e, d.absoluteBottom - a.offsetHeight );
    },
    

    targetWidth: function( e ) {
        if( !this.targetElement )
            return;
        DOM.setWidth( e, this.targetElement.offsetWidth );
    }
}
