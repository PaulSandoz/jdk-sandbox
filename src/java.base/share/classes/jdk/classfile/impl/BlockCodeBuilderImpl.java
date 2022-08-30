/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.classfile.impl;

import jdk.classfile.CodeBuilder;
import jdk.classfile.CodeElement;
import jdk.classfile.Label;
import jdk.classfile.Opcode;
import jdk.classfile.TypeKind;
import jdk.classfile.instruction.LabelTarget;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * BlockCodeBuilder
 */
public sealed class BlockCodeBuilderImpl
        extends NonterminalCodeBuilder
        implements CodeBuilder.BlockCodeBuilder
        permits CatchFinallyBuilderImpl.InlineFinallyBuilder {
    private final CodeBuilder parent;
    private final Label startLabel, endLabel, breakLabel;
    private boolean reachable = true;
    private boolean hasInstructions = false;
    private int topLocal;
    private int terminalMaxLocals;
    private Set<Label> labels;

    public BlockCodeBuilderImpl(CodeBuilder parent, Label breakLabel) {
        super(parent);
        this.parent = parent;
        this.startLabel = parent.newLabel();
        this.endLabel = parent.newLabel();
        this.breakLabel = Objects.requireNonNull(breakLabel);
    }

    public void start() {
        topLocal = topLocal(parent);
        terminalMaxLocals = topLocal(terminal);
        terminal.with((LabelTarget) startLabel);
    }

    public void end() {
        terminal.with((LabelTarget) endLabel);
        if (terminalMaxLocals != topLocal(terminal)) {
            throw new IllegalStateException("Interference in local variable slot management");
        }
        // @@@ make dead all labels?
    }

    Set<Label> initLabelSet() {
        if (labels == null) {
            labels = new HashSet<>();
            labels.add(startLabel);
        }
        return labels;
    }

    @Override
    public Label newLabel() {
        Label l = terminal.newLabel();
        initLabelSet().add(l);
        return l;
    }

    public Set<Label> labels() {
        return Collections.unmodifiableSet(initLabelSet());
    }

    public boolean reachable() {
        return reachable;
    }

    public boolean isEmpty() {
        return !hasInstructions;
    }

    public CodeBuilder parent() {
        return parent;
    }

    private int topLocal(CodeBuilder parent) {
        return switch (parent) {
            case BlockCodeBuilderImpl b -> b.topLocal;
            case ChainedCodeBuilder b -> topLocal(b.terminal);
            case DirectCodeBuilder b -> b.curTopLocal();
            case BufferedCodeBuilder b -> b.curTopLocal();
        };
    }

    @Override
    public CodeBuilder with(CodeElement element) {
        if (element instanceof LabelTarget lt) {
            // Only labels created by the block may be bound in the block
            if (!labels.contains(lt.label())) {
                throw new IllegalArgumentException("Label target's label cannot be bound");
            }
            // Ensure label target is not processed by parent blocks
            terminal.with(element);
        } else {
            parent.with(element);
        }

        Opcode op = element.opcode();
        hasInstructions |= !op.isPseudo();

        if (reachable) {
            if (op.isUnconditionalBranch())
                reachable = false;
        }
        else if (op == Opcode.LABEL_TARGET) {
            reachable = true;
        }
        return this;
    }

    @Override
    public Label startLabel() {
        return startLabel;
    }

    @Override
    public Label endLabel() {
        return endLabel;
    }

    @Override
    public int allocateLocal(TypeKind typeKind) {
        int retVal = topLocal;
        topLocal += typeKind.slotSize();
        return retVal;
    }

    @Override
    public Label breakLabel() {
        return breakLabel;
    }
}
