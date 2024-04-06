/*
 * Copyright (c) 2008 the original author or authors.
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

import {Extension} from "./Extension.js";
import {Z85} from "./Z85.js";

/**
 * Client-side extension that converts binary JavaScript objects
 * (ArrayBuffer, DataView and TypedArrays) into a textual
 * representation suitable for JSON, using the Z85 algorithm.
 */
export class BinaryExtension extends Extension {
    incoming(message) {
        if (!/^\/meta\//.test(message.channel)) {
            const ext = message.ext;
            if (ext) {
                const binaryExt = ext.binary;
                if (binaryExt) {
                    message.data.data = Z85.decode(message.data.data);
                }
            }
        }
        return message;
    };

    outgoing(message) {
        if (!/^\/meta\//.test(message.channel)) {
            const ext = message.ext;
            if (ext) {
                const binaryExt = ext.binary;
                if (binaryExt) {
                    message.data.data = Z85.encode(message.data.data);
                }
            }
        }
        return message;
    };
}
