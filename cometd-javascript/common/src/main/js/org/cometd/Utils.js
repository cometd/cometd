;(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        // AMD Definition
        define([], factory);
    } else if (typeof require === 'function' && typeof exports === 'object') {
        // CommonJS
        module.exports = factory();
    } else {
        // Global Definition

        // Namespaces for the cometd implementation
        root.org = root.org || {};
        root.org.cometd = org.cometd || {};

        var namespace = root.org.cometd;
        namespace.Utils = factory();
    }
}(this, function () {
    'use strict';

    var Utils = {};

    Utils.isString = function(value) {
        if (value === undefined || value === null) {
            return false;
        }
        return typeof value === 'string' || value instanceof String;
    };

    Utils.isArray = function(value) {
        if (value === undefined || value === null) {
            return false;
        }
        return value instanceof Array;
    };

    /**
     * Returns whether the given element is contained into the given array.
     * @param element the element to check presence for
     * @param array the array to check for the element presence
     * @return the index of the element, if present, or a negative index if the element is not present
     */
    Utils.inArray = function(element, array) {
        for (var i = 0; i < array.length; ++i) {
            if (element === array[i]) {
                return i;
            }
        }
        return -1;
    };

    Utils.setTimeout = function(cometd, funktion, delay) {
        return window.setTimeout(function() {
            try {
                cometd._debug('Invoking timed function', funktion);
                funktion();
            } catch (x) {
                cometd._debug('Exception invoking timed function', funktion, x);
            }
        }, delay);
    };

    Utils.clearTimeout = function(timeoutHandle) {
        window.clearTimeout(timeoutHandle);
    };

    return Utils;
}));
