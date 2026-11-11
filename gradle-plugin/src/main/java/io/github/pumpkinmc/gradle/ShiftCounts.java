package io.github.pumpkinmc.gradle;

import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.Program;
import org.teavm.model.instructions.BinaryInstruction;
import org.teavm.model.instructions.BinaryOperation;
import org.teavm.model.instructions.IntegerConstantInstruction;
import org.teavm.model.instructions.NumericOperandType;

public final class ShiftCounts implements ClassHolderTransformer {
    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram();
            if (program == null) continue;

            for (BasicBlock block : program.getBasicBlocks()) {
                for (Instruction instruction : block) {
                    if (instruction instanceof BinaryInstruction shift && isShift(shift.getOperation())) mask(program, shift);
                }
            }
        }
    }

    private static boolean isShift(BinaryOperation operation) {
        return operation == BinaryOperation.SHIFT_LEFT || operation == BinaryOperation.SHIFT_RIGHT || operation == BinaryOperation.SHIFT_RIGHT_UNSIGNED;
    }

    private static void mask(Program program, BinaryInstruction shift) {
        IntegerConstantInstruction limit = new IntegerConstantInstruction();
        limit.setConstant(shift.getOperandType() == NumericOperandType.LONG ? 63 : 31);
        limit.setReceiver(program.createVariable());
        limit.setLocation(shift.getLocation());

        BinaryInstruction count = new BinaryInstruction(BinaryOperation.AND, NumericOperandType.INT);
        count.setFirstOperand(shift.getSecondOperand());
        count.setSecondOperand(limit.getReceiver());
        count.setReceiver(program.createVariable());
        count.setLocation(shift.getLocation());

        shift.insertPrevious(limit);
        shift.insertPrevious(count);
        shift.setSecondOperand(count.getReceiver());
    }
}
