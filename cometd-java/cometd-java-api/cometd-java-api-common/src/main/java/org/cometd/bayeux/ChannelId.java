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
package org.cometd.bayeux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>Reification of a {@link Channel#getId() channel id} with methods to test properties
 * and compare with other {@link ChannelId}s.</p>
 * <p>A {@link ChannelId} breaks the channel id into path segments so that, for example,
 * {@code /foo/bar} breaks into {@code ["foo","bar"]}.</p>
 * <p>{@link ChannelId} can be wild, when they end with one or two wild characters {@code "*"};
 * a {@link ChannelId} is shallow wild if it ends with one wild character (for example {@code /foo/bar/*})
 * and deep wild if it ends with two wild characters (for example {@code /foo/bar/**}).</p>
 * <p>{@link ChannelId} can be a template, when a segment contains variable names surrounded by
 * braces, for example {@code /foo/{var_name}}. Variable names can only be made of characters
 * defined by the {@link Pattern \w} regular expression character class.</p>
 */
public class ChannelId {
    public static final String WILD = "*";
    public static final String DEEPWILD = "**";
    // Escape closing brace to support Android.
    private static final Pattern VAR = Pattern.compile("\\{(\\w+)\\}");

    private final ReentrantLock lock = new ReentrantLock();
    private final String _id;
    private volatile String[] _segments;
    private int _wild;
    private List<String> _wilds;
    private List<String> _all;
    private String _parent;
    private List<String> _vars;

    /**
     * Constructs a new {@code ChannelId} with the given id
     *
     * @param id the channel id in string form
     */
    public ChannelId(String id) {
        if (id == null || id.length() == 0 || !Bayeux.Validator.isValidChannelId(id)) {
            throw new IllegalArgumentException("Invalid channel id: " + id);
        }

        id = id.trim();
        if (id.charAt(id.length() - 1) == '/') {
            id = id.substring(0, id.length() - 1);
        }

        _id = id;
    }

    private void resolve() {
        if (_segments == null) {
            lock.lock();
            try {
                if (_segments == null) {
                    resolve(_id);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void resolve(String name) {
        String[] segments = name.substring(1).split("/");
        if (segments.length < 1) {
            throw new IllegalArgumentException("Invalid channel id: " + this);
        }

        for (int i = 1, size = segments.length; i <= size; ++i) {
            String segment = segments[i - 1];
            if (i < size && (WILD.equals(segment) || DEEPWILD.equals(segment))) {
                throw new IllegalArgumentException("Invalid channel id: " + this);
            }

            Matcher matcher = VAR.matcher(segment);
            if (matcher.matches()) {
                if (_vars == null) {
                    _vars = new ArrayList<>();
                }
                _vars.add(matcher.group(1));
            }

            if (i == size) {
                _wild = DEEPWILD.equals(segment) ? 2 : WILD.equals(segment) ? 1 : 0;
            }
        }

        if (_vars == null) {
            _vars = List.of();
        } else {
            _vars = List.copyOf(_vars);
        }

        if (_wild > 0) {
            if (!_vars.isEmpty()) {
                throw new IllegalArgumentException("Invalid channel id: " + this);
            }
        }
        _wilds = List.copyOf(resolveWilds(segments));

        List<String> all = new ArrayList<>();
        all.add(_id);
        all.addAll(_wilds);
        _all = List.copyOf(all);

        _parent = segments.length == 1 ? null : name.substring(0, name.length() - segments[segments.length - 1].length() - 1);

        _segments = segments;
    }

    private List<String> resolveWilds(String[] segments) {
        boolean addShallow = _wild == 0;
        int length = _wild > 1 ? segments.length - 1 : segments.length;
        List<String> wilds = new ArrayList<>(length + 1);
        StringBuilder b = new StringBuilder("/");
        for (int i = 1; i <= length; ++i) {
            String segment = segments[i - 1];
            if (segment.trim().length() == 0) {
                throw new IllegalArgumentException("Invalid channel id: " + this);
            }

            wilds.add(0, b + "**");

            if (VAR.matcher(segment).matches()) {
                addShallow = i == length;
                break;
            }

            if (i < length) {
                b.append(segment).append('/');
            }
        }
        if (addShallow) {
            wilds.add(0, b + "*");
        }
        return wilds;
    }

    /**
     * <p>Returns the normalized channel id string.</p>
     * <p>Normalization involves trimming white spaces and removing trailing slashes.</p>
     *
     * @return the normalized channel id string
     */
    public String getId() {
        return _id;
    }

    /**
     * @return whether this {@code ChannelId} is either {@link #isShallowWild() shallow wild}
     * or {@link #isDeepWild() deep wild}
     */
    public boolean isWild() {
        resolve();
        return _wild > 0;
    }

    /**
     * <p>Shallow wild {@code ChannelId}s end with a single wild character {@code "*"}
     * and {@link #matches(ChannelId) match} non wild channels with
     * the same {@link #depth() depth}.</p>
     * <p>Example: {@code /foo/*} matches {@code /foo/bar}, but not {@code /foo/bar/baz}.</p>
     *
     * @return whether this {@code ChannelId} is a shallow wild channel id
     */
    public boolean isShallowWild() {
        return isWild() && !isDeepWild();
    }

    /**
     * <p>Deep wild {@code ChannelId}s end with a double wild character "**"
     * and {@link #matches(ChannelId) match} non wild channels with
     * the same or greater {@link #depth() depth}.</p>
     * <p>Example: {@code /foo/**} matches {@code /foo/bar} and {@code /foo/bar/baz}.</p>
     *
     * @return whether this {@code ChannelId} is a deep wild channel id
     */
    public boolean isDeepWild() {
        resolve();
        return _wild > 1;
    }

    /**
     * <p>A {@code ChannelId} is a meta {@code ChannelId} if it starts with {@code "/meta/"}.</p>
     *
     * @return whether the first segment is "meta"
     */
    public boolean isMeta() {
        return isMeta(_id);
    }

    /**
     * <p>A {@code ChannelId} is a service {@code ChannelId} if it starts with {@code "/service/"}.</p>
     *
     * @return whether the first segment is "service"
     */
    public boolean isService() {
        return isService(_id);
    }

    /**
     * @return whether this {@code ChannelId} is neither {@link #isMeta() meta} nor {@link #isService() service}
     */
    public boolean isBroadcast() {
        return isBroadcast(_id);
    }

    /**
     * @return whether this {@code ChannelId} is a template, that is it contains segments
     * that identify a variable name between braces, such as {@code /foo/{var_name}}.
     * @see #bind(ChannelId)
     * @see #getParameters()
     */
    public boolean isTemplate() {
        resolve();
        return !_vars.isEmpty();
    }

    /**
     * @return the list of variable names if this ChannelId is a template,
     * otherwise an empty list.
     * @see #isTemplate()
     */
    public List<String> getParameters() {
        resolve();
        return _vars;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof ChannelId that) {
            return _id.equals(that._id);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return _id.hashCode();
    }

    /**
     * <p>Tests whether this {@code ChannelId} matches the given {@code ChannelId}.</p>
     * <p>If the given {@code ChannelId} is {@link #isWild() wild},
     * then it matches only if it is equal to this {@code ChannelId}.</p>
     * <p>If this {@code ChannelId} is non-wild,
     * then it matches only if it is equal to the given {@code ChannelId}.</p>
     * <p>Otherwise, this {@code ChannelId} is either shallow or deep wild, and
     * matches {@code ChannelId}s with the same number of equal segments (if it is
     * shallow wild), or {@code ChannelId}s with the same or a greater number of
     * equal segments (if it is deep wild).</p>
     *
     * @param channelId the channelId to match
     * @return true if this {@code ChannelId} matches the given {@code ChannelId}
     */
    public boolean matches(ChannelId channelId) {
        resolve();

        if (channelId.isWild()) {
            return equals(channelId);
        }

        switch (_wild) {
            case 0 -> {
                return equals(channelId);
            }
            case 1 -> {
                if (channelId._segments.length != _segments.length) {
                    return false;
                }
                for (int i = _segments.length - 1; i-- > 0; ) {
                    if (!_segments[i].equals(channelId._segments[i])) {
                        return false;
                    }
                }
                return true;
            }
            case 2 -> {
                if (channelId._segments.length < _segments.length) {
                    return false;
                }
                for (int i = _segments.length - 1; i-- > 0; ) {
                    if (!_segments[i].equals(channelId._segments[i])) {
                        return false;
                    }
                }
                return true;
            }
            default -> {
                throw new IllegalStateException("Invalid wild value " + _wild + " for " + this);
            }
        }
    }

    /**
     * <p>If this {@code ChannelId} is a template, and the given {@code target} {@code ChannelId}
     * is non-wild and non-template, and the two have the same {@link #depth()}, then binds
     * the variable(s) defined in this template with the values of the segments defined by
     * the target {@code ChannelId}.</p>
     * <p>For example:</p>
     * <pre>
     * // template and target match.
     * Map&lt;String, String&gt; bindings = new ChannelId("/a/{var1}/c/{var2}").bind(new ChannelId("/a/foo/c/bar"));
     * bindings: {"var1": "foo", "var2": "bar"}
     *
     * // template has 2 segments, target has only 1 segment.
     * bindings = new ChannelId("/a/{var1}").bind(new ChannelId("/a"))
     * bindings = {}
     *
     * // template has 2 segments, target too many segments.
     * bindings = new ChannelId("/a/{var1}").bind(new ChannelId("/a/b/c"))
     * bindings = {}
     *
     * // same number of segments, but no match on non-variable segments.
     * bindings = new ChannelId("/a/{var1}").bind(new ChannelId("/b/c"))
     * bindings = {}
     * </pre>
     * <p>The returned map may not preserve the order of variables present in the template {@code ChannelId}.</p>
     *
     * @param target the non-wild, non-template {@code ChannelId} to bind
     * @return a map withe the bindings, or an empty map if no binding was possible
     * @see #isTemplate()
     */
    public Map<String, String> bind(ChannelId target) {
        if (!isTemplate() || target.isTemplate() || target.isWild() || depth() != target.depth()) {
            return Map.of();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < _segments.length; ++i) {
            String thisSegment = getSegment(i);
            String thatSegment = target.getSegment(i);

            Matcher matcher = VAR.matcher(thisSegment);
            if (matcher.matches()) {
                result.put(matcher.group(1), thatSegment);
            } else {
                if (!thisSegment.equals(thatSegment)) {
                    return Map.of();
                }
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return getId();
    }

    /**
     * @return how many segments this {@code ChannelId} is made of
     * @see #getSegment(int)
     */
    public int depth() {
        resolve();
        return _segments.length;
    }

    /**
     * @param id the channel to test
     * @return whether this {@code ChannelId} is an ancestor of the given {@code ChannelId}
     * @see #isParentOf(ChannelId)
     */
    public boolean isAncestorOf(ChannelId id) {
        resolve();

        if (isWild() || depth() >= id.depth()) {
            return false;
        }

        for (int i = _segments.length; i-- > 0; ) {
            if (!_segments[i].equals(id._segments[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param id the channel to test
     * @return whether this {@code ChannelId} is the parent of the given {@code ChannelId}
     * @see #isAncestorOf(ChannelId)
     */
    public boolean isParentOf(ChannelId id) {
        resolve();

        if (isWild() || depth() != id.depth() - 1) {
            return false;
        }

        for (int i = _segments.length; i-- > 0; ) {
            if (!_segments[i].equals(id._segments[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the channel string parent of this {@code ChannelId},
     * or null if this {@code ChannelId} has only one segment
     * @see #isParentOf(ChannelId)
     */
    public String getParent() {
        resolve();
        return _parent;
    }

    /**
     * @param i the segment index
     * @return the i-nth segment of this channel, or null if no such segment exist
     * @see #depth()
     */
    public String getSegment(int i) {
        resolve();
        if (i >= _segments.length) {
            return null;
        }
        return _segments[i];
    }

    /**
     * @return The list of wilds channels that match this channel, or
     * the empty list if this channel is already wild.
     * @deprecated use {@link #getWildIds()} instead
     */
    @Deprecated
    public List<String> getWilds() {
        resolve();
        return isWild() ? List.of() : _wilds;
    }

    /**
     * <p>Returns the list of wild channels that match this channel.</p>
     * <p>For channel {@code /foo/bar/baz}, returns {@code [/foo/bar/*, /foo/bar/**, /foo/**, /**]}.</p>
     * <p>For channel {@code /foo/bar/*}, returns {@code [/foo/bar/**, /foo/**, /**]}.</p>
     * <p>For channel {@code /foo/bar/**}, returns {@code [/foo/**, /**]}.</p>
     * <p>For channel {@code /*}, returns {@code [/**]}.</p>
     * <p>For channel {@code /**}, returns {@code []}, an empty list.</p>
     *
     * @return The list of wilds channels that match this channel.
     */
    public List<String> getWildIds() {
        resolve();
        return _wilds;
    }

    /**
     * @return a list with this channel id and its wild channel ids.
     * @see #getId()
     * @see #getWildIds()
     */
    public List<String> getAllIds() {
        resolve();
        return _all;
    }

    /**
     * <p>Returns the regular part of this ChannelId: the part
     * of the channel id from the beginning until the first occurrence
     * of a parameter or a wild character.</p>
     * <p>Examples:</p>
     * <table>
     * <caption>ChannelId.regularPart Examples</caption>
     * <thead>
     * <tr>
     * <th>Channel</th>
     * <th>Regular Part</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td>/foo</td>
     * <td>/foo</td>
     * </tr>
     * <tr>
     * <td>/foo/*</td>
     * <td>/foo</td>
     * </tr>
     * <tr>
     * <td>/foo/bar/**</td>
     * <td>/foo/bar</td>
     * </tr>
     * <tr>
     * <td>/foo/{p}</td>
     * <td>/foo</td>
     * </tr>
     * <tr>
     * <td>/foo/bar/{p}</td>
     * <td>/foo/bar</td>
     * </tr>
     * <tr>
     * <td>/*</td>
     * <td>{@code null}</td>
     * </tr>
     * <tr>
     * <td>/**</td>
     * <td>{@code null}</td>
     * </tr>
     * <tr>
     * <td>/{p}</td>
     * <td>{@code null}</td>
     * </tr>
     * </tbody>
     * </table>
     *
     * @return the regular part of this channel
     */
    public String getRegularPart() {
        resolve();
        if (isWild()) {
            return getParent();
        }
        if (!isTemplate()) {
            return _id;
        }
        int regular = depth() - getParameters().size();
        if (regular <= 0) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < regular; ++i) {
            result.append("/").append(getSegment(i));
        }
        return result.toString();
    }

    /**
     * <p>Helper method to test if the string form of a {@code ChannelId}
     * represents a {@link #isMeta() meta} {@code ChannelId}.</p>
     *
     * @param channelId the channel id to test
     * @return whether the given channel id is a meta channel id
     */
    public static boolean isMeta(String channelId) {
        return channelId != null && channelId.startsWith("/meta/");
    }

    /**
     * <p>Helper method to test if the string form of a {@code ChannelId}
     * represents a {@link #isService() service} {@code ChannelId}.</p>
     *
     * @param channelId the channel id to test
     * @return whether the given channel id is a service channel id
     */
    public static boolean isService(String channelId) {
        return channelId != null && channelId.startsWith("/service/");
    }

    /**
     * <p>Helper method to test if the string form of a {@code ChannelId}
     * represents a {@link #isBroadcast() broadcast} {@code ChannelId}.</p>
     *
     * @param channelId the channel id to test
     * @return whether the given channel id is a broadcast channel id
     */
    public static boolean isBroadcast(String channelId) {
        return !isMeta(channelId) && !isService(channelId);
    }
}
