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

export * from "./Transport";
export * from "./LongPollingTransport";
export * from "./WebSocketTransport";

import { Transport } from "./Transport";

export interface TransportRegistry {
    getTransportTypes(): string[];

    findTransportTypes(version: string, crossDomain: boolean, url: string): string[];

    negotiateTransport(types: string[], version: string, crossDomain: boolean, url: string): Transport | null;

    find(type: string): Transport | null;
}

export interface Advice {
    interval?: number;
    maxInterval?: number;
    "multiple-clients"?: boolean;
    reconnect?: "retry" | "handshake" | "none";
    timeout?: number;
    hosts?: string[];
}

export interface Failure extends Advice {
    connectionType?: string;
    reason?: string;
    httpCode?: string;
    websocketCode?: string;
}

export interface Message<T = any> {
    advice?: Advice;
    channel: string;
    clientId?: string;
    connectionType?: string;
    data?: T;
    error?: string;
    ext?: object;
    failure?: Failure;
    id?: string;
    minimumVersion?: string;
    reestablish?: boolean;
    subscription?: string[];
    successful?: boolean;
    supportedConnectionTypes?: string[];
    timestamp?: string;
    version?: string;
}

export type Callback<T = any> = (message: Message<T>) => void;

export type LogLevel = "warn" | "info" | "debug";

export interface Configuration {
    url: string;
    logLevel?: LogLevel;
    logger?: (level: LogLevel, arguments: string[]) => void;
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

export type Status = "disconnected" | "handshaking" | "connecting" | "connected" | "disconnecting";

export interface Extension {
    incoming?(message: Message): Message | null;
    outgoing?(message: Message): Message | null;
    registered?(name: string, cometd: CometD): void;
    unregistered?(): void;
}

export class CometD {
    constructor(name?: string);

    registerTransport(type: string, transport: Transport, index?: number): boolean;

    unregisterTransport(type: string): Transport | null;

    unregisterTransports(): void;

    getTransportTypes(): string[];

    findTransport(name: string): Transport | null;

    getTransportRegistry(): TransportRegistry;

    configure(options: Configuration | string): void;

    handshake<T = any>(handshakeCallback?: Callback<T>): void;
    handshake<T = any>(handshakeProps: object, handshakeCallback?: Callback<T>): void;

    disconnect<T = any>(disconnectCallback?: Callback<T>): void;
    disconnect<T = any>(disconnectProps: object, disconnectCallback?: Callback<T>): void;

    batch(group: () => void): void;

    addListener<T = any>(channel: string, messageCallback: Callback<T>): ListenerHandle;

    removeListener(handle: ListenerHandle): void;

    clearListeners(): void;

    subscribe<T = any>(channel: string, messageCallback: Callback<T>, subscribeCallback?: Callback<T>): SubscriptionHandle;
    subscribe<T = any>(channel: string, messageCallback: Callback<T>, subscribeProps: object, subscribeCallback?: Callback<T>): SubscriptionHandle;

    unsubscribe<T = any>(handle: SubscriptionHandle, unsubscribeCallback?: Callback<T>): void;
    unsubscribe<T = any>(handle: SubscriptionHandle, unsubscribeProps: object, unsubscribeCallback?: Callback<T>): void;

    resubscribe(handle: SubscriptionHandle, subscribeProps?: object): SubscriptionHandle;

    clearSubscriptions(): void;

    publish<T = any>(channel: string, content: any, publishCallback?: Callback<T>): void;
    publish<T = any>(channel: string, content: any, publishProps: object, publishCallback?: Callback<T>): void;

    publishBinary<T = any>(channel: string, data: any, last: boolean, publishCallback?: Callback<T>): void;
    publishBinary<T = any>(channel: string, data: any, last: boolean, meta: object, publishCallback?: Callback<T>): void;
    publishBinary<T = any>(channel: string, data: any, last: boolean, meta: object, publishProps: object, publishCallback?: Callback<T>): void;

    remoteCall<T = any>(target: string, content: any, callback?: Callback<T>): void;
    remoteCall<T = any>(target: string, content: any, timeout: number, callback?: Callback<T>): void;
    remoteCall<T = any>(target: string, content: any, timeout: number, callProps: object, callback?: Callback<T>): void;

    remoteCallBinary<T = any>(target: string, data: any, last: boolean, callback?: Callback<T>): void;
    remoteCallBinary<T = any>(target: string, data: any, last: boolean, meta: object, callback?: Callback<T>): void;
    remoteCallBinary<T = any>(target: string, data: any, last: boolean, meta: object, timeout: number, callback?: Callback<T>): void;
    remoteCallBinary<T = any>(target: string, data: any, last: boolean, meta: object, timeout: number, callProps: object, callback?: Callback<T>): void;

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

    onListenerException?: (error: Error, subscription: unknown, listener: SubscriptionHandle, message: Message) => void;
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
