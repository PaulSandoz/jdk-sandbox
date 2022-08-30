package jdk.classfile.impl;

import jdk.classfile.CodeBuilder;
import jdk.classfile.CodeElement;
import jdk.classfile.Label;
import jdk.classfile.Opcode;
import jdk.classfile.TypeKind;
import jdk.classfile.instruction.BranchInstruction;
import jdk.classfile.instruction.ReturnInstruction;

import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class CatchFinallyBuilderImpl implements CodeBuilder.CatchFinallyBuilder {
    final Map<ClassDesc, Consumer<CodeBuilder.BlockCodeBuilder>> catchHandlers;
    Consumer<CodeBuilder.BlockCodeBuilder> finallyHandler;

    public CatchFinallyBuilderImpl() {
        this.catchHandlers = new LinkedHashMap<>();
    }

    @Override
    public CodeBuilder.CatchFinallyBuilder catching(ClassDesc exceptionType,
                                                    Consumer<CodeBuilder.BlockCodeBuilder> catchHandler) {
        Objects.requireNonNull(catchHandler);
        Objects.requireNonNull(exceptionType);

        if (catchHandlers.putIfAbsent(exceptionType, catchHandler) != null) {
            throw new IllegalArgumentException("Existing catch handler catches exception of type: " + exceptionType);
        }

        return this;
    }

    @Override
    public void finally_(Consumer<CodeBuilder.BlockCodeBuilder> finallyHandler) {
        Objects.requireNonNull(finallyHandler);

        if (this.finallyHandler != null) {
            throw new IllegalArgumentException("Existing finally handler");
        }

        this.finallyHandler = finallyHandler;
    }

    public void applyHandlers(CodeBuilder b, Consumer<CodeBuilder.BlockCodeBuilder> tryHandler) {
        Label tryCatchEnd = b.newLabel();

        // Try block

        InlineFinallyBuilder tryBlockWithFinally;
        BlockCodeBuilderImpl tryBlock;
        if (finallyHandler != null) {
            tryBlock = tryBlockWithFinally = new InlineFinallyBuilder(b, tryCatchEnd);
        } else {
            tryBlock = new BlockCodeBuilderImpl(b, tryCatchEnd);
            tryBlockWithFinally = null;
        }
        tryBlock.start();
        {
            tryHandler.accept(tryBlock);
            if (!tryBlock.isEmpty() && tryBlock.reachable()) {
                tryBlock.branchInstruction(Opcode.GOTO, tryCatchEnd);
            }
        }
        tryBlock.end();

        if (tryBlock.isEmpty()) {
            if (tryBlockWithFinally != null) {
                tryBlockWithFinally.inlineFinallyBlock();
            }
            return;
        }


        // Catch blocks

        List<Label> tryCodeRegions;
        BlockCodeBuilderImpl finallyBlock;
        if (tryBlockWithFinally != null) {
            tryCodeRegions = tryBlockWithFinally.codeRegions;
            finallyBlock = new BlockCodeBuilderImpl(b, tryCatchEnd);
        } else {
            tryCodeRegions = List.of(tryBlock.startLabel(), tryBlock.endLabel());
            finallyBlock = null;
        }
        List<Label> catchCodeRegions = new ArrayList<>();
        catchHandlers.forEach((exceptionType, catchHandler) -> {
            InlineFinallyBuilder catchBlockWithFinally;
            BlockCodeBuilderImpl catchBlock;
            if (finallyHandler != null) {
                catchBlock = catchBlockWithFinally = new InlineFinallyBuilder(b, tryCatchEnd);
            } else {
                catchBlock = new BlockCodeBuilderImpl(b, tryCatchEnd);
                catchBlockWithFinally = null;
            }

            // Declare catch/catch-all regions before the start finally block
            // This ensures correct synchronous stack tracking
            // Produce in same order as the java compiler
            // Declare catch regions for try block
            forEachRegion(tryCodeRegions,
                    (start, end) -> b.exceptionCatch(start, end, catchBlock.startLabel(), exceptionType));
            // Declare catch-all regions for try block
            if (finallyBlock != null) {
                forEachRegion(tryCodeRegions,
                        (start, end) -> b.exceptionCatchAll(start, end, finallyBlock.startLabel()));
            }

            catchBlock.start();
            {
                catchHandler.accept(catchBlock);
                if (catchBlock.reachable()) {
                    catchBlock.branchInstruction(Opcode.GOTO, tryCatchEnd);
                }
            }
            catchBlock.end();

            if (catchBlockWithFinally != null) {
                catchCodeRegions.addAll(catchBlockWithFinally.codeRegions);
            }
        });


        // Finally block, that throws

        if (finallyBlock != null) {
            // Declare catch-all regions before the start finally block
            // This ensures correct synchronous stack tracking

            // Declare catch-all regions for try block, if no catch blocks
            if (catchHandlers.isEmpty()) {
                forEachRegion(tryCodeRegions,
                        (start, end) -> b.exceptionCatchAll(start, end, finallyBlock.startLabel()));
            }

            // Declare catch-all regions for catch blocks, if any
            forEachRegion(catchCodeRegions,
                    (start, end) -> b.exceptionCatchAll(start, end, finallyBlock.startLabel()));

            finallyBlock.start();
            {
                int t = finallyBlock.allocateLocal(TypeKind.ReferenceType);
                finallyBlock.astore(t);
                finallyHandler.accept(finallyBlock);
                if (finallyBlock.reachable()) {
                    finallyBlock.aload(t);
                    finallyBlock.athrow();
                }
            }
            finallyBlock.end();
        }

        b.labelBinding(tryCatchEnd);
    }

    void forEachRegion(List<Label> regions, BiConsumer<Label, Label> consumer) {
        for (int i = 0; i < regions.size(); i += 2) {
            var start = regions.get(i);
            var end = regions.get(i + 1);
            consumer.accept(start, end);
        }
    }

    final class InlineFinallyBuilder extends BlockCodeBuilderImpl {
        final List<Label> codeRegions;

        InlineFinallyBuilder(CodeBuilder parent, Label breakLabel) {
            super(parent, breakLabel);
            codeRegions = new ArrayList<>();
        }

        boolean markStartOfCodeRegion = true;

        @Override
        public void end() {
            super.end();

            if (!markStartOfCodeRegion) {
                codeRegions.add(endLabel());
            }
        }

        @Override
        public CodeBuilder with(CodeElement e) {
            if (isBlockExitingInstruction(e)) {
                if (!markStartOfCodeRegion) {
                    markStartOfCodeRegion = true;
                    // End of code region
                    codeRegions.add(parent().newBoundLabel());
                }

                inlineFinallyBlock();

            } else if (markStartOfCodeRegion && !e.opcode().isPseudo()) {
                markStartOfCodeRegion = false;
                // Start of code region
                codeRegions.add(parent().newBoundLabel());
            }

            super.with(e);
            return this;
        }

        boolean isBlockExitingInstruction(CodeElement e) {
            return switch (e) {
                // @@@ This assumes a label that is not a member of the block's label set
                //     branches to an ancestor block rather than to an descendant block.
                //     We need a way to determine if a label is dead
                //     i.e. after the end of a block, all associated labels become dead
                case BranchInstruction bi -> !labels().contains(bi.target());
                case ReturnInstruction ri -> true;
                default -> false;
            };
        }

        void inlineFinallyBlock() {
            var finallyBlock = new BlockCodeBuilderImpl(parent(), breakLabel());
            finallyBlock.start();
            {
                finallyHandler.accept(finallyBlock);
            }
            finallyBlock.end();
        }
    }
}
