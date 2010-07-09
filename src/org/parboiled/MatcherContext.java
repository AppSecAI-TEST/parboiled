/*
 * Copyright (C) 2009 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.parboiled;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.jetbrains.annotations.NotNull;
import org.parboiled.common.Reference;
import org.parboiled.common.StringUtils;
import org.parboiled.errors.BasicParseError;
import org.parboiled.errors.ParseError;
import org.parboiled.errors.ParserRuntimeException;
import org.parboiled.matchers.*;
import org.parboiled.support.*;

import java.util.ArrayList;
import java.util.List;

import static org.parboiled.errors.ErrorUtils.printParseError;
import static org.parboiled.support.ParseTreeUtils.findNode;
import static org.parboiled.support.ParseTreeUtils.findNodeByPath;

/**
 * <p>The Context implementation orchestrating most of the matching process.</p>
 * <p>The parsing process works as following:</br>
 * After the rule tree (which is in fact a directed and potentially even cyclic graph of {@link Matcher} instances)
 * has been created a root MatcherContext is instantiated for the root rule (Matcher).
 * A subsequent call to {@link #runMatcher()} starts the parsing process.</p>
 * <p>The MatcherContext delegates to a given {@link MatchHandler} to call {@link Matcher#match(MatcherContext)},
 * passing itself to the Matcher which executes its logic, potentially calling sub matchers.
 * For each sub matcher the matcher creates/initializes a sub context with {@link Matcher#getSubContext(MatcherContext)}
 * and then calls {@link #runMatcher()} on it.</p>
 * <p>This basically creates a stack of MatcherContexts, each corresponding to their rule matchers. The MatcherContext
 * instances serve as companion objects to the matchers, providing them with support for building the
 * parse tree nodes, keeping track of input locations and error recovery.</p>
 * <p>At each point during the parsing process the matchers and action expressions have access to the current
 * MatcherContext and all "open" parent MatcherContexts through the {@link #getParent()} chain.</p>
 * <p>For performance reasons sub context instances are reused instead of being recreated. If a MatcherContext instance
 * returns null on a {@link #getMatcher()} call it has been retired (is invalid) and is waiting to be reinitialized
 * with a new Matcher by its parent</p>
 *
 * @param <V> the node value type
 */
public class MatcherContext<V> implements Context<V> {

    private final InputBuffer inputBuffer;
    private final List<ParseError> parseErrors;
    private final MatchHandler<V> matchHandler;
    private final Reference<Node<V>> lastNodeRef;
    private final MatcherContext<V> parent;
    private final int level;
    private final boolean fastStringMatching;

    private MatcherContext<V> subContext;
    private int startIndex;
    private int currentIndex;
    private char currentChar;
    private Matcher<V> matcher;
    private Node<V> node;
    private List<Node<V>> subNodes = ImmutableList.of();
    private V nodeValue;
    private V treeValue;
    private int intTag;
    private boolean hasError;
    private boolean nodeSuppressed;

    /**
     * Initializes a new root MatcherContext.
     *
     * @param inputBuffer        the InputBuffer for the parsing run
     * @param parseErrors        the parse error list to create ParseError objects in
     * @param matchHandler       the MatcherHandler to use for the parsing run
     * @param matcher            the root matcher
     * @param fastStringMatching <p>Fast string matching "short-circuits" the default practice of treating string rules
     *                           as simple Sequence of character rules. When fast string matching is enabled strings are
     *                           matched at once, without relying on inner CharacterMatchers. Even though this can lead
     *                           to significant increases of parsing performance it does not play well with error
     *                           reporting and recovery, which relies on character level matches.
     *                           Therefore the {@link ReportingParseRunner} and {@link RecoveringParseRunner}
     *                           implementations only enable fast string matching during their basic first parsing run
     *                           and disable it once the input has proven to contain errors.</p>
     */
    public MatcherContext(@NotNull InputBuffer inputBuffer, @NotNull List<ParseError> parseErrors,
                          @NotNull MatchHandler<V> matchHandler, @NotNull Matcher<V> matcher,
                          boolean fastStringMatching) {
        this(inputBuffer, parseErrors, matchHandler, new Reference<Node<V>>(), null, 0, fastStringMatching);
        this.currentChar = inputBuffer.charAt(0);
        this.matcher = ProxyMatcher.unwrap(matcher);
        this.nodeSuppressed = matcher.isNodeSuppressed();
    }

    private MatcherContext(@NotNull InputBuffer inputBuffer, @NotNull List<ParseError> parseErrors,
                           @NotNull MatchHandler<V> matchHandler, @NotNull Reference<Node<V>> lastNodeRef,
                           MatcherContext<V> parent, int level, boolean fastStringMatching) {
        this.inputBuffer = inputBuffer;
        this.parseErrors = parseErrors;
        this.matchHandler = matchHandler;
        this.lastNodeRef = lastNodeRef;
        this.parent = parent;
        this.level = level;
        this.fastStringMatching = fastStringMatching;
    }

    @Override
    public String toString() {
        return getPath().toString();
    }

    //////////////////////////////// CONTEXT INTERFACE ////////////////////////////////////

    public MatcherContext<V> getParent() {
        return parent;
    }

    public MatcherContext<V> getSubContext() {
        // if the subContext has a null matcher it has been retired and is invalid
        return subContext != null && subContext.matcher != null ? subContext : null;
    }

    @NotNull
    public InputBuffer getInputBuffer() {
        return inputBuffer;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public Matcher<V> getMatcher() {
        return matcher;
    }

    public char getCurrentChar() {
        return currentChar;
    }

    @NotNull
    public List<ParseError> getParseErrors() {
        return parseErrors;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public String getNodeText(Node<V> node) {
        return ParseTreeUtils.getNodeText(node, inputBuffer);
    }

    @NotNull
    public MatcherPath<V> getPath() {
        return new MatcherPath<V>(this);
    }

    public int getLevel() {
        return level;
    }

    public boolean fastStringMatching() {
        return fastStringMatching;
    }
    public V getNodeValue() {
        return nodeValue;
    }

    public void setNodeValue(V value) {
        this.nodeValue = value;
    }

    public V getTreeValue() {
        return nodeValue != null ? nodeValue : treeValue;
    }

    public Node<V> getNodeByPath(String path) {
        return findNodeByPath(subNodes, path);
    }

    public Node<V> getNodeByLabel(String labelPrefix) {
        return findNode(subNodes, new LabelPrefixPredicate<V>(labelPrefix));
    }

    public Node<V> getLastNode() {
        return lastNodeRef.get();
    }

    @NotNull
    public List<Node<V>> getSubNodes() {
        return subNodes;
    }

    public boolean inPredicate() {
        return matcher instanceof TestMatcher || matcher instanceof TestNotMatcher ||
                parent != null && parent.inPredicate();
    }

    public boolean isNodeSuppressed() {
        return nodeSuppressed;
    }

    public boolean hasError() {
        return hasError;
    }

    public V getPrevValue() {
        MatcherContext<V> sequenceContext = getPrevSequenceContext();
        return sequenceContext.subContext.nodeValue;
    }

    public String getPrevText() {
        MatcherContext<V> sequenceContext = getPrevSequenceContext();
        MatcherContext<V> prevContext = sequenceContext.subContext;
        return sequenceContext.hasError ? sequenceContext.getNodeText(getLastNode()) :
                inputBuffer.extract(prevContext.startIndex, prevContext.currentIndex);
    }

    public int getPrevStartIndex() {
        MatcherContext<V> sequenceContext = getPrevSequenceContext();
        return sequenceContext.subContext.startIndex;
    }

    public int getPrevEndIndex() {
        MatcherContext<V> sequenceContext = getPrevSequenceContext();
        return sequenceContext.subContext.currentIndex;
    }

    private MatcherContext<V> getPrevSequenceContext() {
        MatcherContext<V> actionContext = this;

        // we need to find the deepest currently active context
        while (actionContext.subContext != null && actionContext.subContext.matcher != null) {
            actionContext = actionContext.subContext;
        }
        MatcherContext<V> sequenceContext = actionContext.getParent();

        // make sure all the constraints are met
        Checks.ensure(
                ProxyMatcher.unwrap(VarFramingMatcher.unwrap(sequenceContext.matcher)) instanceof SequenceMatcher &&
                        sequenceContext.intTag > 0 &&
                        actionContext.matcher instanceof ActionMatcher,
                "Illegal getPrevValue() call, only valid in Sequence rule actions that are not in first position");
        return sequenceContext;
    }

    //////////////////////////////// PUBLIC ////////////////////////////////////

    public void setMatcher(Matcher<V> matcher) {
        this.matcher = matcher;
    }

    public void setStartIndex(int startIndex) {
        Preconditions.checkArgument(startIndex >= 0);
        this.startIndex = startIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        Preconditions.checkArgument(currentIndex >= 0);
        this.currentIndex = currentIndex;
        currentChar = inputBuffer.charAt(currentIndex);
    }

    public void advanceIndex(int delta) {
        if (currentIndex != Characters.EOI) currentIndex += delta;
        currentChar = inputBuffer.charAt(currentIndex);
    }

    public Node<V> getNode() {
        return node;
    }

    public int getIntTag() {
        return intTag;
    }

    public void setIntTag(int intTag) {
        this.intTag = intTag;
    }

    public void markError() {
        if (!hasError) {
            hasError = true;
            if (parent != null) parent.markError();
        }
    }

    public void clearNodeSuppression() {
        if (nodeSuppressed) {
            nodeSuppressed = false;
            if (parent != null) parent.clearNodeSuppression();
        }
    }

    @SuppressWarnings({"ConstantConditions"})
    public void createNode() {
        nodeValue = getTreeValue();
        if (nodeValue != null && parent != null) {
            parent.treeValue = nodeValue;
        }
        if (!nodeSuppressed && !matcher.isNodeSkipped()) {
            node = new NodeImpl<V>(matcher, subNodes, startIndex, currentIndex, nodeValue, hasError);

            MatcherContext<V> nodeParentContext = parent;
            if (nodeParentContext != null) {
                while (nodeParentContext.getMatcher().isNodeSkipped()) {
                    nodeParentContext = nodeParentContext.getParent();
                    Checks.ensure(nodeParentContext != null, "Root rule must not be marked @SkipNode");
                }
                nodeParentContext.addChildNode(node);
            }
            lastNodeRef.set(node);
        }
    }

    public final MatcherContext<V> getBasicSubContext() {
        return subContext == null ?

                // init new level
                subContext = new MatcherContext<V>(inputBuffer, parseErrors, matchHandler, lastNodeRef, this,
                        level + 1, fastStringMatching) :

                // reuse existing instance
                subContext;
    }

    public final MatcherContext<V> getSubContext(Matcher<V> matcher) {
        MatcherContext<V> sc = getBasicSubContext();
        sc.matcher = matcher;
        sc.startIndex = sc.currentIndex = currentIndex;
        sc.currentChar = currentChar;
        sc.node = null;
        sc.subNodes = ImmutableList.of();
        sc.nodeValue = null;
        sc.treeValue = null;
        sc.nodeSuppressed = nodeSuppressed || this.matcher.areSubnodesSuppressed() || matcher.isNodeSuppressed();
        sc.hasError = false;
        return sc;
    }

    public boolean runMatcher() {
        try {
            if (matchHandler.match(this)) {
                if (parent != null) {
                    parent.currentIndex = currentIndex;
                    parent.currentChar = currentChar;
                }
                matcher = null; // "retire" this context
                return true;
            }
            matcher = null; // "retire" this context until is "activated" again by a getSubContext(...) on the parent
            return false;
        } catch (ParserRuntimeException e) {
            throw e; // don't wrap, just bubble up
        } catch (Throwable e) {
            throw new ParserRuntimeException(e,
                    printParseError(new BasicParseError(inputBuffer, currentIndex,
                            StringUtils.escape(String.format("Error while parsing %s '%s' at input position",
                                    matcher instanceof ActionMatcher ? "action" : "rule", getPath()))), inputBuffer));
        }
    }

    //////////////////////////////// PRIVATE ////////////////////////////////////

    @SuppressWarnings({"fallthrough"})
    private void addChildNode(@NotNull Node<V> node) {
        int size = subNodes.size();
        if (size == 0) {
            subNodes = ImmutableList.of(node);
            return;
        }
        if (size == 1) {
            Node<V> node0 = subNodes.get(0);
            subNodes = new ArrayList<Node<V>>(4);
            subNodes.add(node0);
        }
        subNodes.add(node);
    }
}
