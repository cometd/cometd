/*
 * Copyright (c) 2008-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
angular.module('cometd-reload', ['cometd'])
    .run(['$rootScope', 'cometd', function($rootScope, cometd) {
        function _angularize(fn) {
            return function() {
                var self = this;
                var args = arguments;
                return $rootScope.$apply(function() {
                    return fn.apply(self, args);
                });
            }
        }

        var reloadExtension = new org.cometd.ReloadExtension();
        reloadExtension._receive = _angularize(reloadExtension._receive);
        cometd.registerExtension('reload', reloadExtension);
    }]);
