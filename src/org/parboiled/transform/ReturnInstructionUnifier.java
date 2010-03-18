/*
 * Copyright (C) 2009-2010 Mathias Doenitz
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

package org.parboiled.transform;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;

/**
 * Replaces all "non-last" return instructions with goto instructions to the last return instruction.
 * If a method contains only one return instruction the transformer does nothing.
 */
class ReturnInstructionUnifier implements MethodTransformer, Opcodes {

    private final MethodTransformer next;

    public ReturnInstructionUnifier(MethodTransformer next) {
        this.next = next;
    }

    public void transform(@NotNull ParserClassNode classNode, @NotNull RuleMethod method) throws Exception {
        AbstractInsnNode current = method.instructions.getLast();

        // find last return
        while (current.getOpcode() != ARETURN) {
            current = current.getPrevious();
        }

        LabelNode lastReturnLabel = new LabelNode();
        method.instructions.insertBefore(current, lastReturnLabel);

        // iterate backwards up to first instructions
        while ((current = current.getPrevious()) != null) {
            if (current.getOpcode() == ARETURN) {
                JumpInsnNode gotoInstruction = new JumpInsnNode(GOTO, lastReturnLabel);
                method.instructions.set(current, gotoInstruction);
                current = gotoInstruction;
            }
        }

        if (next != null) next.transform(classNode, method);
    }

}