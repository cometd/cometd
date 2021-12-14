/*
 * Copyright (c) 2008-2021 the original author or authors.
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

export interface Transport {
    readonly type: string;
    url: string;
    accept(version: string, crossDomain: boolean, url: string): boolean;
}

export interface TransportRegistry {
    getTransportTypes(): string[];

    findTransportTypes(version: string, crossDomain: boolean, url: string): string[];

    negotiateTransport(types: string[], version: string, crossDomain: boolean, url: string): Transport | null;

    find(type: string): Transport | null;
}

export interface Advice {
    interval?: number;
    maxInterval?: number;
    'multiple-clients'?: boolean;
    reconnect?: 'retry' | 'handshake' | 'none';
    timeout?: number;
    hosts?: string[];
}

export interface Message {
    advice?: Advice;
    channel: string;
    clientId?: string;
    connectionType?: string;
    data?: any;
    error?: string;
    ext?: object;
    id?: string;
    minimumVersion?: string;
    reestablish?: boolean;
    subscription?: string[];
    successful?: boolean;
    supportedConnectionTypes?: string[];
    timestamp?: string;
    version?: string;
}

export type Callback = (message: Message) => void;

export type LogLevel = 'warn' | 'info' | 'debug';

export interface Configuration {
    url: string;
    logLevel?: LogLevel;
    useWorkerScheduler?: boolean;
    protocol?: string;
    stickyReconnect?: boolean;
    connectTimeout?: number;
    maxConnections?: number;
    backoffIncrement?: number;
    maxBackoff?: number;
    maxNetworkDelay?: number;
    requestHeaders?: object;
    appendMessageTypeToURL?: boolean;
    autoBatch?: boolean;
    urls?: object;
    maxURILength?: number;
    maxSendBayeuxMessageSize?: number;
    advice?: Advice;
}

export interface ListenerHandle {
}

export interface SubscriptionHandle {
}

export type Status = 'disconnected' | 'handshaking' | 'connecting' | 'connected' | 'disconnecting';

export interface Extension {
    incoming?(message: Message): Message | null;

    outgoing?(message: Message): Message | null;
}

export class CometD {
    constructor(name?: string);

    registerTransport(type: string, transport: Transport, index?: number): boolean;

    unregisterTransport(type: string): Transport | null;

    unregisterTransports(): void;

    getTransportTypes(): string[];

    findTransport(name: string): Transport | null;

    getTransportRegistry(): TransportRegistry;

    configure(options: Configuration): void;

    handshake(handshakeCallback?: Callback): void;
    handshake(handshakeProps: object, handshakeCallback?: Callback): void;

    disconnect(disconnectCallback?: Callback): void;
    disconnect(disconnectProps: object, disconnectCallback?: Callback): void;

    batch(group: () => void): void;

    addListener(channel: string, messageCallback: Callback): ListenerHandle;

    removeListener(handle: ListenerHandle): void;

    clearListeners(): void;

    subscribe(channel: string, messageCallback: Callback, subscribeCallback?: Callback): SubscriptionHandle;
    subscribe(channel: string, messageCallback: Callback, subscribeProps: object, subscribeCallback?: Callback): SubscriptionHandle;

    unsubscribe(handle: SubscriptionHandle, unsubscribeCallback?: Callback): void;
    unsubscribe(handle: SubscriptionHandle, unsubscribeProps: object, unsubscribeCallback?: Callback): void;

    resubscribe(handle: SubscriptionHandle, subscribeProps?: object): SubscriptionHandle;

    clearSubscriptions(): void;

    publish(channel: string, content: any, publishCallback?: Callback): void;
    publish(channel: string, content: any, publishProps: object, publishCallback?: Callback): void;

    publishBinary(channel: string, data: any, last: boolean, publishCallback?: Callback): void;
    publishBinary(channel: string, data: any, last: boolean, meta: object, publishCallback?: Callback): void;
    publishBinary(channel: string, data: any, last: boolean, meta: object, publishProps: object, publishCallback?: Callback): void;

    remoteCall(target: string, content: any, callback?: Callback): void;
    remoteCall(target: string, content: any, timeout: number, callback?: Callback): void;
    remoteCall(target: string, content: any, timeout: number, callProps: object, callback?: Callback): void;

    remoteCallBinary(target: string, data: any, last: boolean, callback?: Callback): void;
    remoteCallBinary(target: string, data: any, last: boolean, meta: object, callback?: Callback): void;
    remoteCallBinary(target: string, data: any, last: boolean, meta: object, timeout: number, callback?: Callback): void;
    remoteCallBinary(target: string, data: any, last: boolean, meta: object, timeout: number, callProps: object, callback?: Callback): void;

    getStatus(): Status;

    isDisconnected(): boolean;

    setBackoffIncrement(period: number): void;

    getBackoffIncrement(): number;

    getBackoffPeriod(): number;

    setLogLevel(level: LogLevel): void;

    registerExtension(name: string, extension: Extension): boolean;

    unregisterExtension(name: string): boolean;

    getExtension(name: string): Extension | null;

    getName(): string;

    getClientId(): string | null;

    getURL(): string | null;

    getTransport(): Transport | null;

    getConfiguration(): Configuration;

    getAdvice(): Advice;

    setTimeout(fn: () => void, delay: number): any;

    clearTimeout(handle: any): void;

    reload?(): void;

    websocketEnabled?: boolean;
}

export type Bytes = number[] | ArrayBuffer | DataView |
    Int8Array | Uint8Array | Uint8ClampedArray |
    Int16Array | Uint16Array |
    Int32Array | Uint32Array;

export interface Z85 {
    encode(bytes: Bytes): string;

    decode(string: string): ArrayBuffer;
}

export class AckExtension implements Extension {
}

export class BinaryExtension implements Extension {
}

export class ReloadExtension implements Extension {
}

export class TimeStampExtension implements Extension {
}

export class TimeSyncExtension implements Extension {
}
