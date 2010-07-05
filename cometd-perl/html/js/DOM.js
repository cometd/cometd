/*
DOM Library
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


/* Node class */

if( !defined( window.Node ) )
    Node = {};

try {
    extend( Node, {
        ELEMENT_NODE: 1,
        ATTRIBUTE_NODE: 2,
        TEXT_NODE: 3,
        CDATA_SECTION_NODE: 4,  
        COMMENT_NODE: 8,    
        DOCUMENT_NODE: 9,
        DOCUMENT_FRAGMENT_NODE: 11
    } );
} catch( e ) {}


/* DOM class */

if( !defined( window.DOM ) )
    DOM = {};


extend( DOM, {
    getElement: function( e ) {
        return (typeof e == "string" || typeof e == "number") ? document.getElementById( e ) : e;
    },
    
    
    reload: function( w ) {
        if( !w || !w.location || !w.location.reload )
            w = window;
        w.location.reload( true );
    },


    addEventListener: function( e, en, f, uc ) {
        try {
            if( e.addEventListener )
                e.addEventListener( en, f, uc );
            else if( e.attachEvent )
                e.attachEvent( "on" + en, f );
            else
                e[ "on" + en ] = f;
        } catch( e ) {}
    },


    removeEventListener: function( e, en, f, uc ) {
        try {
            if( e.removeEventListener )
                e.removeEventListener( en, f, uc );
            else if( e.detachEvent )
                e.detachEvent( "on" + en, f );
            else
                e[ "on" + en ] = undefined;
        } catch( e ) {}
    },
    
    
    focus: function( e ) {
        try {
            e = DOM.getElement( e );
            e.focus();
        } catch( e ) {}
    },


    blur: function( e ) {
        try {
            e = DOM.getElement( e );
            e.blur();
        } catch( e ) {}
    },
    

    /* Form Data */
    
    /**
     * Class: <code>DOM</code><br/>
     * Set any elements in the dialog dom element that correspond to properties of this dialog's 
     * <code>data</code> property to the values of the respective properties in <code>data</code>. 
     * This method also sets a reference (param <code>firstInput</code>) to the first input element in the 
     * form, if it is not set when the method is called.
     * (See the doc for the method <code>open</code>.
     * @param element <code>Node</code> A dom element.
     * @param data <code>Object</code> 
     */
    setFormData: function( el, d ) {
        if ( typeof el == "string" || typeof el == "number" )
            el = DOM.getElement( el );
        if ( !d )
            d = {};
        
        var es = Array.fromPseudo(
            el.getElementsByTagName( "input" ),
            el.getElementsByTagName( "textarea" ),
            el.getElementsByTagName( "select" )
        );

        for ( var i = 0; i < es.length; i++ ) {
            var e = es[ i ];
            var t = e.getAttribute( "type" );
            t = t ? t.toLowerCase() : "";
        
            var tn = e.tagName.toLowerCase();
            var v = d[ e.name ];
            switch( tn ) {
                case "input":
                    if ( t == "image" || t == "submit" )
                        break;
                    
                    if ( t == "radio" ) {
                        if ( d.hasOwnProperty( e.name ) )
                            e.checked = v == e.value ? true : false;
                        break;
                    }
                    
                    if ( t == "checkbox" ) {
                        if ( v instanceof Array )
                            e.checked = ( v.indexOf( e.value ) != -1 ) ? true : false;
                        else
                            e.checked = v == e.value ? true : false;
                        break;
                    }
    
                case "textarea": // Catches "text" input types too.
                    if ( d.hasOwnProperty( e.name ) && defined( v ) )
                        e.value = v;
                    break;
    
                case "select":
                    for( var j = 0; j < e.options.length; j++ ) {
                        if ( e.options[ j ].value == v ) {
                            e.selectedIndex = j;
                            break;
                        }
                    }
                    break;
            }
        }
    },

    
    /**
     * Class: <code>DOM</code><br/>
     * Get all the form element data, outside of blacklisted elements, and 'stuff' it into the dialog's 
     * <code>data</code> property.
     * @param element <code>Node</code> A dom element.
     * @param data <code>Object</code> 
     * @return Object The aggregated form-element data
     */
    getFormData: function( el, d ) {
        if ( typeof el == "string" || typeof el == "number" )
            el = DOM.getElement( el );
        if ( !d )
            d = {};

        var es = Array.fromPseudo(
            el.getElementsByTagName( "input" ),
            el.getElementsByTagName( "textarea" ),
            el.getElementsByTagName( "select" )
        );

        for ( var i = 0; i < es.length; i++ ) {
            var e = es[ i ];
            var t = e.getAttribute( "type" );
            t = t ? t.toLowerCase() : "";
        
            var tn = e.tagName.toLowerCase();
            switch( tn ) {
                case "input":
                    if ( t == "image" || t == "submit" )
                        break;
                        
                    if ( t == "radio" ) {
                        if ( e.checked )
                            d[ e.name.cssToJS() ] = e.value;
                        break;
    
                    } else if ( t == "checkbox" ) {
                        var name = e.name.cssToJS();
                        var value = ( e.checked ) ? e.value : "";
                        
                        if ( d.hasOwnProperty( name ) ) {
                            /* do conversion into array (based on multiple of same name
                             * even if we don't add the empty value
                             */
                            if ( !( d[ name ] instanceof Array ) ) {
                                if ( d[ name ] )
                                    d[ name ] = [ d[ name ] ];
                                else
                                    d[ name ] = [];
                            }
                            
                            var pos = d[ name ].indexOf( e.value );
                            if ( pos != -1 )
                                d[ name ].splice( pos, 1 );
                                
                            if ( value )
                                d[ name ].push( value );
                        } else
                            d[ name ] = value;
                        
                        break;
                    }
                    
                case "textarea":
                    d[ e.name.cssToJS() ] = e.value;
                    break;
                
                case "select":
                    if ( e.selectedIndex < 0 ) 
                        d[ e.name.cssToJS() ] = undefined;
                    else 
                        d[ e.name.cssToJS() ] = e.options[ e.selectedIndex ].value;
                    break;                       
            }
        }

        return d;
    },



    /* style */
    
    getComputedStyle: function( e ) {
        if( e.currentStyle )
            return e.currentStyle;
        var style = {};
        var owner = DOM.getOwnerDocument( e );
        if( owner && owner.defaultView && owner.defaultView.getComputedStyle ) {            
            try {
                style = owner.defaultView.getComputedStyle( e, null );
            } catch( e ) {}
        }
        return style;
    },


    getStyle: function( e, p ) {
        var s = DOM.getComputedStyle( e );
        return s[ p ];
    },


    // given a window (or defaulting to current window), returns
    // object with .x and .y of client's usable area
    getClientDimensions: function( w ) {
        w = w || window;
        var d = {};
        
        // most browsers
        if( w.innerHeight ) {
            d.x = w.innerWidth;
            d.y = w.innerHeight;
            return d;
        }

        // IE6, strict
        var de = w.document.documentElement;
        if( de && de.clientHeight ) {
            d.x = de.clientWidth;
            d.y = de.clientHeight;
            return d;
        }

        // IE, misc
        if( document.body ) {
            d.x = document.body.clientWidth;
            d.y = document.body.clientHeight;
            return d;
        }
        
        return undefined;
    },
    
    
    getDocumentDimensions: function( d ) {
        d = d || document;
        if( d.body.scrollHeight > d.body.offsetHeight )
        	return {
        	    width: d.body.scrollWidth,
        	    height: d.body.scrollHeight
            };
        else
        	return {
        	    width: d.body.offsetWidth,
        	    height: d.body.offsetHeight
            };
    },


    getDimensions: function( e ) {
        if( !e )
            return undefined;

        var style = DOM.getComputedStyle( e );

        return {
            offsetLeft: e.offsetLeft,
            offsetTop: e.offsetTop,
            offsetWidth: e.offsetWidth,
            offsetHeight: e.offsetHeight,
            clientWidth: e.clientWidth,
            clientHeight: e.clientHeight,
            
            offsetRight: e.offsetLeft + e.offsetWidth,
            offsetBottom: e.offsetTop + e.offsetHeight,
            clientLeft: finiteInt( style.borderLeftWidth ) + finiteInt( style.paddingLeft ),
            clientTop: finiteInt( style.borderTopWidth ) + finiteInt( style.paddingTop ),
            clientRight: e.clientLeft + e.clientWidth,
            clientBottom: e.clientTop + e.clientHeight,
            
            marginLeft: style.marginLeft,
            marginTop: style.marginTop,
            marginRight: style.marginRight,
            marginBottom: style.marginBottom
        };
    },


    getAbsoluteDimensions: function( e ) {
        var d = DOM.getDimensions( e );
        if( !d )
            return d;
        d.absoluteLeft = d.offsetLeft;
        d.absoluteTop = d.offsetTop;
        d.absoluteRight = d.offsetRight;
        d.absoluteBottom = d.offsetBottom;
        var bork = 0;
        while( e ) {
            try { // IE 6 sometimes gives an unwarranted error ("htmlfile: Unspecified error").
                e = e.offsetParent;
            } catch ( err ) {
                log( "In DOM.getAbsoluteDimensions: " + err.message ); 
                if ( ++bork > 25 )
                    return null;
            }
            if( !e )
                return d;
            d.absoluteLeft += e.offsetLeft;
            d.absoluteTop += e.offsetTop;
            d.absoluteRight += e.offsetLeft;
            d.absoluteBottom += e.offsetTop;
        }
        return d;
    },
    
    
    getIframeAbsoluteDimensions: function( e ) {
        var d = DOM.getAbsoluteDimensions( e );
        if( !d )
            return d;
        var iframe = DOM.getOwnerIframe( e );
        if( !defined( iframe ) )
            return d;
        
        var d2 = DOM.getIframeAbsoluteDimensions( iframe );
        var scroll = DOM.getWindowScroll( iframe.contentWindow );
        var left = d2.absoluteLeft - scroll.left;
        var top = d2.absoluteTop - scroll.top;
        
        d.absoluteLeft += left;
        d.absoluteTop += top;
        d.absoluteRight += left;
        d.absoluteBottom += top;
        
        return d;
    },
    
    
    setLeft: function( e, v ) { e.style.left = finiteInt( v ) + "px"; },
    setTop: function( e, v ) { e.style.top = finiteInt( v ) + "px"; },
    setRight: function( e, v ) { e.style.right = finiteInt( v ) + "px"; },
    setBottom: function( e, v ) { e.style.bottom = finiteInt( v ) + "px"; },
    setWidth: function( e, v ) { e.style.width = max( 0, finiteInt( v ) ) + "px"; },
    setHeight: function( e, v ) { e.style.height = max( 0, finiteInt( v ) ) + "px"; },
    setZIndex: function( e, v ) { e.style.zIndex = finiteInt( v ); },

    
    getWindowScroll: function( w ) {
        w = w || window;
        var d = w.document;
        var de = d.documentElement;
        var b = d.body;
        
        return {
            left: max( de.scrollLeft, b.scrollLeft, w.scrollX, w.pageXOffset ),
            top: max( de.scrollTop, b.scrollTop, w.scrollY, w.pageYOffset )
        };
    },
    
    
    getAbsoluteCursorPosition: function( ev ) {
        ev = ev || window.event;
        var pos = {
            x: ev.clientX,
            y: ev.clientY
        };
        if ( !navigator.userAgent.toLowerCase().match(/webkit/) ) {
            var s = DOM.getWindowScroll( window );
            pos.x += s.left;
            pos.y += s.top;
        }
        return pos;
    },
    
    
    invisibleStyle: {
        display: "block",
        position: "absolute",
        left: 0,
        top: 0,
        width: 0,
        height: 0,
        margin: 0,
        border: 0,
        padding: 0,
        fontSize: "0.1px",
        lineHeight: 0,
        opacity: 0,
        MozOpacity: 0,
        filter: "alpha(opacity=0)"
    },
    
    
    makeInvisible: function( e ) {
        for( var p in this.invisibleStyle )
            if( this.invisibleStyle.hasOwnProperty( p ) )
                e.style[ p ] = this.invisibleStyle[ p ];
    },


    /* text and selection related methods */
    
    getSelection: function( w, d ) {
        if ( !w )
            w = window;
        return w.getSelection ?
            w.getSelection() :
            ( d ? d.selection : w.document.selection )
    },


    mergeTextNodes: function( n ) {
        var c = 0;
        while( n ) {
            if( n.nodeType == Node.TEXT_NODE && n.nextSibling && n.nextSibling.nodeType == Node.TEXT_NODE ) {
                n.nodeValue += n.nextSibling.nodeValue;
                n.parentNode.removeChild( n.nextSibling );
                c++;
            } else {
                if( n.firstChild )
                    c += DOM.mergeTextNodes( n.firstChild );
                n = n.nextSibling;
            }
        }
        return c;
    },
    
    
    // XXX delete or make this function work everywhere
    selectElement: function( e ) {
        var d = e.ownerDocument;  
        
        // internet explorer  
        if( d.body.createControlRange ) {  
            var r = d.body.createControlRange();  
            r.addElement( e );  
            r.select();  
        }  
    }, 
    
    
    /* dom methods */
    
    isImmutable: function( n ) {
        try {
            if( n.getAttribute( "contenteditable" ) == "false" )
                return true;
        } catch( e ) {}
        return false;
    },
    
    
    getImmutable: function( n ) {
        var immutable = null;
        while( n ) {
            if( DOM.isImmutable( n ) )
                immutable = n;
            n = n.parentNode;
        }
        return immutable;
    },


    getOwnerDocument: function( n ) {
        if( !n )
            return document;
        if( n.ownerDocument )
            return n.ownerDocument;
        if( n.getElementById )
            return n;
        return document;
    },


    getOwnerWindow: function( n ) {
        if( !n )
            return window;
        if( n.parentWindow )
            return n.parentWindow;
        var doc = DOM.getOwnerDocument( n );
        if( doc && doc.defaultView )
            return doc.defaultView;
        return window;
    },
    
    
    getOwnerIframe: function( n ) {
        if( !n )
            return undefined;
        var nw = DOM.getOwnerWindow( n );
        var nd = DOM.getOwnerDocument( n );
        var pw = nw.parent || nw.parentWindow;
        if( !pw )
            return undefined;
        var parentDocument = pw.document;
        var es = parentDocument.getElementsByTagName( "iframe" );
        for( var i = 0; i < es.length; i++ ) {
            var e = es[ i ];
            try {
                var d = e.contentDocument || e.contentWindow.document;
                if( d === nd )
                    return e;
            }catch(err) {};
        }
        return undefined;
    },


    filterElementsByClassName: function( es, cn ) {
        var filtered = [];
        for( var i = 0; i < es.length; i++ ) {
            var e = es[ i ];
            if( DOM.hasClassName( e, cn ) )
                filtered[ filtered.length ] = e;
        }
        return filtered;
    },
    
    
    filterElementsByAttribute: function( es, a ) {
        if( !es )
            return [];
        if( !a )
            return es;
        var f = [];
        for( var i = 0; i < es.length; i++ ) {
            var e = es[ i ];
            if( !e )
                continue;
            try {
                if( e.getAttribute && e.getAttribute( a ) )
                    f.push( e );
            } catch( e ) {}
        }
        return f;
    },


    filterElementsByTagName: function( es, tn ) {
        if( tn == "*" )
            return es;
        var f = [];
        tn = tn.toLowerCase();
        for( var i = 0; i < es.length; i++ ) {
            var e = es[ i ];
            if( e.tagName && e.tagName.toLowerCase() == tn )
                f.push( e );
        }
        return f;
    },


    getElementsByTagAndAttribute: function( r, tn, a ) {
        if( !r )
            r = document;
        var es = r.getElementsByTagName( tn );
        return DOM.filterElementsByAttribute( es, a );
    },
    
    
    getElementsByAttribute: function( r, a ) {
        return DOM.getElementsByTagAndAttribute( r, "*", a );
    },


    getElementsByAttributeAndValue: function( r, a, v ) {
        var es = DOM.getElementsByTagAndAttribute( r, "*", a );
        var filtered = [];
        for ( var i = 0; i < es.length; i++ ) {
            var e = es[ i ];
            try {
                if( e.getAttribute && e.getAttribute( a ) == v ) 
                    filtered.push( es[ i ] );
            } catch( e ) {}
        }
        return filtered;
    },
    

    getElementsByTagAndClassName: function( r, tn, cn ) {
        if( !r )
            r = document;
        var elements = r.getElementsByTagName( tn );
        return DOM.filterElementsByClassName( elements, cn );
    },


    getElementsByClassName: function( r, cn ) {
        return DOM.getElementsByTagAndClassName( r, "*", cn );
    },


    getAncestors: function( n, s ) {
        if( !n )
            return [];
        var as = s ? [ n ] : [];
        n = n.parentNode;
        while( n ) {
            as.push( n );
            n = n.parentNode;
        }
        return as;
    },
    
    
    getAncestorsByTagName: function( n, tn, s ) {
        var es = DOM.getAncestors( n, s );
        return DOM.filterElementsByTagName( es, tn );
    },
    
    
    getFirstAncestorByTagName: function( n, tn, s ) {
        return DOM.getAncestorsByTagName( n, tn, s )[ 0 ];
    },


    getAncestorsByClassName: function( n, cn, s ) {
        var es = DOM.getAncestors( n, s );
        return DOM.filterElementsByClassName( es, cn );
    },


    getFirstAncestorByClassName: function( n, cn, s ) {
        return DOM.getAncestorsByClassName( n, cn, s )[ 0 ];
    },


    getAncestorsByTagAndClassName: function( n, tn, cn, s ) {
        var es = DOM.getAncestorsByTagName( n, tn, s );
        return DOM.filterElementsByClassName( es, cn );
    },


    getFirstAncestorByTagAndClassName: function( n, tn, cn, s ) {
        return DOM.getAncestorsByTagAndClassName( n, tn, cn, s )[ 0 ];
    },


    getPreviousElement: function( n, t ) {
        if ( !t )
            t = Node.ELEMENT_NODE;
        n = n.previousSibling;
        while( n ) {
            if( n.nodeType == Node.ELEMENT_NODE )
                return n;
            n = n.previousSibling;
        }
        return null;
    },


    getNextElement: function( n, t ) {
        if ( !t )
            t = Node.ELEMENT_NODE;
        n = n.nextSibling;
        while( n ) {
            if( n.nodeType == t )
                return n;
            n = n.nextSibling;
        }
        return null;
    },


    isInlineNode: function( n ) {
        // text nodes are inline
        if( n.nodeType == Node.TEXT_NODE )
            return n;

        // document nodes are non-inline
        if( n.nodeType == Node.DOCUMENT_NODE )
            return false;

        // all nonelement nodes are inline
        if( n.nodeType != Node.ELEMENT_NODE )
            return n;

        // br elements are not inline
        if( n.tagName && n.tagName.toLowerCase() == "br" )
            return false;

        // examine the style property of the inline n
        var display = DOM.getStyle( n, "display" ); 
        if( display && display.indexOf( "inline" ) >= 0 ) 
            return n;
    },
    
    
    isTextNode: function( n ) {
        if( n.nodeType == Node.TEXT_NODE )
            return n;
    },
    
    
    isInlineTextNode: function( n ) {
        if( n.nodeType == Node.TEXT_NODE )
            return n;
        if( !DOM.isInlineNode( n ) )
            return null;
    },


    /* this and the following classname functions honor w3c case-sensitive classnames */

    getClassNames: function( e ) {
        if( !e || !e.className )
            return [];
        return e.className.split( /\s+/g );
    },


    hasClassName: function( e, cn ) {
        if( !e || !e.className )
            return false;
        var cs = DOM.getClassNames( e );
        for( var i = 0; i < cs.length; i++ ) {
            if( cs[ i ] == cn )
                return true;
        }
        return false;
    },


    addClassName: function( e, cn ) {
        if( !e || !cn )
            return false;
        var cs = DOM.getClassNames( e );
        for( var i = 0; i < cs.length; i++ ) {
            if( cs[ i ] == cn )
                return true;
        }
        cs.push( cn );
        e.className = cs.join( " " );
        return false;
    },


    removeClassName: function( e, cn ) {
        var r = false;
        if( !e || !e.className || !cn )
            return r;
        var cs = (e.className && e.className.length)
            ? e.className.split( /\s+/g )
            : [];
        var ncs = [];
        /* support regex */
        if( cn instanceof RegExp ) {
            for( var i = 0; i < cs.length; i++ ) {
                if ( cn.test( cs[ i ] ) ) {
                    r = true;
                    continue;
                }
                ncs.push( cs[ i ] );
            }
        } else {
            for( var i = 0; i < cs.length; i++ ) {
                if( cs[ i ] == cn ) {
                    r = true;
                    continue;
                }
                ncs.push( cs[ i ] );
            }
        }
        if( r )
            e.className = ncs.join( " " );
        return r;
    },
    
    
    /* tree manipulation methods */
    
    replaceWithChildNodes: function( n ) {
        var firstChild = n.firstChild;
        var parentNode = n.parentNode;
        while( n.firstChild )
            parentNode.insertBefore( n.removeChild( n.firstChild ), n );
        parentNode.removeChild( n );
        return firstChild;
    },
    
    
    /* eolas patent */
    
    activateEmbeds: function( doc ) {
        if( !defined( window.clipboardData ) )
            return;
        if( !doc ) 
            doc = document;
        var es = doc.getElementsByTagName( "embed" );
        for ( var j = 0; j < es.length; j++ )
           es[ j ].parentElement.innerHTML = es[ j ].parentElement.innerHTML;            
    },
    
    
    /* factory methods */
    
    createInvisibleInput: function( d ) {
        if( !d )
            d = window.document;
        var e = document.createElement( "input" );
        e.setAttribute( "autocomplete", "off" );
        e.autocomplete = "off";
        DOM.makeInvisible( e );
        return e;
    },

    
    /* attribute manipulation */
    
    getMouseEventAttribute: function( ev, a ) {
        if( !a )
            return;
        var es = DOM.getAncestors( ev.target, true );
        for( var i = 0; i < es.length; i++ ) {
            try {
                var e = es[ i ];
                var v = e.getAttribute ? e.getAttribute( a ) : null;
                if( v ) {
                    ev.attributeElement = e;
                    ev.attribute = v;
                    return v;
                }
            } catch( e ) {}
        }
    },
    

    setElementAttribute: function( e, a, v ) {
        /* safari workaround
         * safari's setAttribute assumes you want to use a namespace
         * when you have a colon in your attribute
         */
        if ( navigator.userAgent.toLowerCase().match(/webkit/) ) {
            var at = e.attributes;
            for ( var i = 0; i < at.length; i++ )
                if ( at[ i ].name == a )
                    return at[ i ].nodeValue = v;

            /* fallback to setAttribute */
        }
        
        e.setAttribute( a, v );
    },


    swapAttributes: function( e, tg, at ) {
        var ar = e.getAttribute( tg );
        if( !ar )
            return false;
        
        /* clone the node with all children */
        if ( e.tagName.toLowerCase() == 'script' ) {
            /* only clone and replace script tags */
            var cl = e.cloneNode( true );
            if ( !cl )
                return false;

            DOM.setElementAttribute( cl, at, ar );
            cl.removeAttribute( tg );
        
            /* replace new, old */
            return e.parentNode.replaceChild( cl, e );
        } else {
            DOM.setElementAttribute( e, at, ar );
            e.removeAttribute( tg );
        }
    }
} );


$ = DOM.getElement;
