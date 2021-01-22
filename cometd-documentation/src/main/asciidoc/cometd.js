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

document.addEventListener('DOMContentLoaded', () => dynamicTOC());

function dynamicTOC() {
    // Bind a click listener to all section titles.
    const content = document.getElementById('content');
    const sectionTitles = content.querySelectorAll('a.link');
    for (const sectionTitle of sectionTitles) {
        sectionTitle.addEventListener('click', event => collapseThenExpand(event.target.hash));
    }

    // Bind a click listener to all TOC titles.
    const toc = document.getElementById('toc');
    const tocTitles = toc.querySelectorAll('a');
    for (const tocTitle of tocTitles) {
        tocTitle.addEventListener('click', event => collapseThenExpand(event.target.hash));
    }

    // Add the icons to TOC nodes.
    const nodes = toc.querySelectorAll('li');
    for (const node of nodes) {
        const span = document.createElement('span');
        const css = span.classList;
        if (node.querySelector(':scope > ul')) {
            css.add('toc-toggle');
            // Font-Awesome classes.
            css.add('fa');
            css.add('fa-caret-right');
            span.addEventListener('click', event => toggle(event.target));
        } else {
            css.add('toc-item');
            // The "icon" is the &bull; HTML entity.
            span.appendChild(document.createTextNode('•'));
        }
        node.prepend(span);
    }

    collapseThenExpand(document.location.hash);
}

function collapseThenExpand(hash) {
    const toc = document.getElementById('toc');
    if (hash) {
        const current = toc.querySelector('a.toc-current');
        if (current) {
            current.classList.remove('toc-current');
        }
        const anchor = toc.querySelector('a[href="' + hash + '"');
        if (anchor) {
            anchor.classList.add('toc-current');
            collapse(toc);
            expand(anchor.parentNode);
        }
    } else {
        collapse(toc);
    }
}

function collapse(node) {
    const sections = node.querySelectorAll('ul');
    for (const section of sections) {
        const css = section.classList;
        // Always show first level TOC titles.
        if (!css.contains('sectlevel1')) {
            css.add('hidden');
        }
    }
    // Show the collapsed icon.
    const spans = node.querySelectorAll('span.toc-toggle');
    for (const span of spans) {
        const css = span.classList;
        css.remove('fa-caret-down');
        css.add('fa-caret-right');
    }
}

function expand(node) {
    const root = document.getElementById('toc').querySelector('ul');
    // Show the current node and its ancestors.
    let parent = node;
    while (parent !== root) {
        // Show the node.
        parent.classList.remove('hidden');
        // Show the expanded icon.
        const span = parent.querySelector(':scope > span.toc-toggle');
        if (span) {
            const css = span.classList;
            css.remove('fa-caret-right');
            css.add('fa-caret-down');
        }
        parent = parent.parentNode;
    }
    // Show the children.
    const children = node.querySelector(':scope > ul');
    if (children) {
        children.classList.remove('hidden');
    }
}

function toggle(span) {
    const css = span.classList;
    const expanded = css.contains('fa-caret-down');
    if (expanded) {
        collapse(span.parentNode);
    } else {
        expand(span.parentNode);
    }
}
