package com.salesforce.graph.symbols.apex.schema;

import com.salesforce.graph.DeepCloneable;
import com.salesforce.graph.DeepCloneableApexValue;
import com.salesforce.graph.ops.ApexStandardLibraryUtil;
import com.salesforce.graph.ops.ApexValueUtil;
import com.salesforce.graph.symbols.SymbolProvider;
import com.salesforce.graph.symbols.apex.ApexStandardValue;
import com.salesforce.graph.symbols.apex.ApexStringValue;
import com.salesforce.graph.symbols.apex.ApexValue;
import com.salesforce.graph.symbols.apex.ApexValueBuilder;
import com.salesforce.graph.symbols.apex.ApexValueVisitor;
import com.salesforce.graph.vertex.InvocableVertex;
import com.salesforce.graph.vertex.InvocableWithParametersVertex;
import com.salesforce.graph.vertex.MethodCallExpressionVertex;
import com.salesforce.graph.vertex.MethodVertex;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

public final class FieldSetMember extends ApexStandardValue<FieldSetMember>
        implements DeepCloneable<FieldSetMember>, DeepCloneableApexValue<FieldSetMember> {
    public static final String TYPE = ApexStandardLibraryUtil.Type.SCHEMA_FIELD_SET_MEMBER;

    public static String METHOD_GET_S_OBJECT_FIELD = "getSObjectField";

    private final FieldSet fieldSet;

    public FieldSetMember(FieldSet fieldSet, ApexValueBuilder builder) {
        super(TYPE, builder);
        this.fieldSet = fieldSet;
    }

    private FieldSetMember(FieldSetMember other) {
        this(other, other.getReturnedFrom().orElse(null), other.getInvocable().orElse(null));
    }

    private FieldSetMember(
            FieldSetMember other,
            @Nullable ApexValue<?> returnedFrom,
            @Nullable InvocableVertex invocable) {
        super(other, returnedFrom, invocable);
        this.fieldSet = other.fieldSet;
    }

    @Override
    public FieldSetMember deepClone() {
        return new FieldSetMember(this);
    }

    @Override
    public FieldSetMember deepCloneForReturn(
            @Nullable ApexValue<?> returnedFrom, @Nullable InvocableVertex invocable) {
        return new FieldSetMember(this, returnedFrom, invocable);
    }

    @Override
    public <U> U accept(ApexValueVisitor<U> visitor) {
        return visitor.visit(this);
    }

    public FieldSet getFieldSet() {
        return fieldSet;
    }

    @Override
    public Optional<ApexValue<?>> apply(MethodCallExpressionVertex vertex, SymbolProvider symbols) {
        ApexValueBuilder builder = ApexValueBuilder.get(symbols).returnedFrom(this, vertex);
        final String methodName = vertex.getMethodName();

        return _applyMethod(builder, methodName);
    }

    @Override
    public Optional<ApexValue<?>> executeMethod(
            InvocableWithParametersVertex invocableExpression,
            MethodVertex method,
            SymbolProvider symbols) {
        ApexValueBuilder builder =
                ApexValueBuilder.get(symbols)
                        .returnedFrom(this, invocableExpression)
                        .methodVertex(method);

        String methodName = method.getName();

        ApexValue<?> apexValue = _applyMethod(builder, methodName).orElse(null);

        if (apexValue == null) {
            apexValue = ApexValueUtil.synthesizeReturnedValue(builder, method);
        }
        return Optional.ofNullable(apexValue);
    }

    private Optional<ApexValue<?>> _applyMethod(ApexValueBuilder builder, String methodName) {
        if (methodName.equalsIgnoreCase(METHOD_GET_S_OBJECT_FIELD)) {
            // We don't know the field name, create an indeterminant string
            ApexStringValue fieldName = builder.deepClone().buildString();
            return Optional.of(
                    builder.buildSObjectField(fieldSet.getSObjectType().get(), fieldName));
        }
        return Optional.empty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        FieldSetMember that = (FieldSetMember) o;
        return Objects.equals(fieldSet, that.fieldSet);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), fieldSet);
    }
}
