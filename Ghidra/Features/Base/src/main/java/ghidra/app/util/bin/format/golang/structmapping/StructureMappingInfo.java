/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.bin.format.golang.structmapping;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

import ghidra.program.model.data.*;
import ghidra.program.model.listing.CodeUnit;
import ghidra.util.InvalidNameException;
import ghidra.util.exception.DuplicateNameException;

/**
 * Contains immutable information about a structure mapped class needed to deserialize
 * a new object from the data found in a Ghidra program.
 *  
 * @param <T> the class that is being mapped into a structure
 */
public class StructureMappingInfo<T> {

	/**
	 * Returns the name of the structure data type that will define the binary layout 
	 * of the mapped fields in the target class.
	 * 
	 * @param targetClass structure mapped class
	 * @return the structure name
	 */
	public static String getStructureDataTypeNameForClass(Class<?> targetClass) {
		StructureMapping sma = targetClass.getAnnotation(StructureMapping.class);
		return sma != null ? sma.structureName() : null;
	}

	/**
	 * Returns the mapping info for a class, using annotations found in that class.
	 * 
	 * @param <T> structure mapped class
	 * @param targetClass structure mapped class
	 * @param structDataType Ghidra {@link DataType} that defines the binary layout of the mapped
	 * fields of the class, or null if this is a self-reading {@link StructureReader} class  
	 * @return new {@link StructureMappingInfo} for the specified class
	 * @throws IllegalArgumentException if targetClass isn't tagged as a structure mapped class
	 */
	public static <T> StructureMappingInfo<T> fromClass(Class<T> targetClass,
			Structure structDataType) {
		StructureMapping sma = targetClass.getAnnotation(StructureMapping.class);
		if (sma == null) {
			throw new IllegalArgumentException(
				"Missing @StructureMapping annotation on " + targetClass.getSimpleName());
		}

		return new StructureMappingInfo<>(targetClass, structDataType, sma);
	}

	private final Class<T> targetClass;
	private final ObjectInstanceCreator<T> instanceCreator;

	private final String structureName;
	private final Structure structureDataType;	// null if variable length fields

	private final List<FieldMappingInfo<T>> fields = new ArrayList<>();
	private final List<FieldOutputInfo<T>> outputFields = new ArrayList<>();
	private final List<StructureMarkupFunction<T>> markupFuncs = new ArrayList<>();
	private final List<Field> contextFields = new ArrayList<>();
	private final List<Method> afterMethods;
	private final boolean useFieldMappingInfo;
	private Field structureContextField;

	private StructureMappingInfo(Class<T> targetClass, Structure structDataType,
			StructureMapping sma) {
		this.targetClass = targetClass;
		this.structureDataType = structDataType;
		this.structureName = structureDataType != null
				? structureDataType.getName()
				: sma.structureName();
		this.useFieldMappingInfo = !StructureReader.class.isAssignableFrom(targetClass);
		this.instanceCreator = findInstanceCreator();

		readFieldInfo(targetClass);
		Collections.sort(outputFields,
			(foi1, foi2) -> Integer.compare(foi1.getOrdinal(), foi2.getOrdinal()));

		afterMethods =
			ReflectionHelper.getMarkedMethods(targetClass, AfterStructureRead.class, null, true);

		List<Method> markupGetters =
			ReflectionHelper.getMarkedMethods(targetClass, Markup.class, null, true);
		for (Method markupGetterMethod : markupGetters) {
			markupFuncs.add(createMarkupFuncFromGetter(markupGetterMethod));
		}

		for (PlateComment pca : ReflectionHelper.getAnnotations(targetClass, PlateComment.class,
			null)) {
			addPlateCommentMarkupFuncs(pca);
		}
	}

	public String getDescription() {
		return "%s-%s".formatted(targetClass.getSimpleName(), structureName);
	}

	public Structure getStructureDataType() {
		return structureDataType;
	}

	public String getStructureName() {
		return structureName;
	}

	public int getStructureLength() {
		if (structureDataType == null) {
			throw new IllegalArgumentException();
		}
		return structureDataType.getLength();
	}

	public Class<T> getTargetClass() {
		return targetClass;
	}

	public ObjectInstanceCreator<T> getInstanceCreator() {
		return instanceCreator;
	}

	public List<FieldMappingInfo<T>> getFields() {
		return fields;
	}

	public List<Method> getAfterMethods() {
		return afterMethods;
	}

	/**
	 * Deserializes a structure mapped instance by assigning values to its 
	 * {@link FieldMapping &#64;FieldMapping mapped} java fields.
	 * 
	 * @param context {@link StructureContext}
	 * @throws IOException if error reading the structure
	 */
	public void readStructure(StructureContext<T> context) throws IOException {
		T newInstance = context.getStructureInstance();
		if (newInstance instanceof StructureReader<?> selfReader) {
			selfReader.readStructure();
		}
		else {
			for (FieldMappingInfo<T> fieldInfo : fields) {
				FieldContext<T> fieldReadContext = context.createFieldContext(fieldInfo, true);
				FieldReadFunction<T> readFunc = fieldInfo.getReaderFunc();
				if (readFunc == null) {
					throw new IOException("Missing read info for field: " + fieldInfo.getField());
				}
				Object value = readFunc.get(fieldReadContext);
				assignField(fieldReadContext, value);
			}
			context.reader.setPointerIndex(context.getStructureEnd());
		}
	}

	public List<StructureMarkupFunction<T>> getMarkupFuncs() {
		return markupFuncs;
	}

	/**
	 * Creates a new customized {@link Structure structure data type} for a variable length
	 * structure mapped class.
	 * 
	 * @param context {@link StructureContext} of a variable length structure mapped instance
	 * @return new {@link Structure structure data type} with a name that encodes the size 
	 * information of the variable length fields
	 * @throws IOException if error creating the Ghidra data type
	 */
	public Structure createStructureDataType(StructureContext<T> context) throws IOException {
		// used to create a structure that has variable length fields

		Structure newStruct = new StructureDataType(
			context.getDataTypeMapper().getDefaultVariableLengthStructCategoryPath(),
			structureName,
			0,
			context.getDataTypeMapper().getDTM());

		// TODO: set struct packing?

		String nameSuffix = "";
		for (FieldOutputInfo<T> foi : outputFields) {
			long structSizeBefore = getStructLength(newStruct);
			foi.getOutputFunc().addFieldToStructure(context, newStruct, foi);
			long sizeDelta = getStructLength(newStruct) - structSizeBefore;
			if (foi.isVariableLength()) {
				nameSuffix += "_%d".formatted(sizeDelta);
			}
		}
		if (!nameSuffix.isEmpty()) {
			try {
				newStruct.setName(structureName + nameSuffix);
			}
			catch (InvalidNameException | DuplicateNameException e) {
				throw new IOException(e);
			}
		}
		return newStruct;
	}

	/**
	 * Reaches into a structure mapped instance and extracts its StructureContext field value.
	 * 
	 * @param structureInstance instance to query
	 * @return {@link StructureContext}, or null if error extracting value
	 */
	@SuppressWarnings("unchecked")
	public StructureContext<T> recoverStructureContext(T structureInstance) {
		try {
			if (structureContextField != null) {
				return ReflectionHelper.getFieldValue(structureInstance, structureContextField,
					StructureContext.class);
			}
		}
		catch (IOException e) {
			// ignore, drop thru return null
		}
		return null;
	}

	/**
	 * Initializes any {@link ContextField} fields in a new structure instance.
	 * 
	 * @param context {@link StructureContext}
	 * @throws IOException if error assigning values to context fields in the structure mapped
	 * instance
	 */
	public void assignContextFieldValues(StructureContext<T> context) throws IOException {
		Class<?> dataTypeMapperType = context.getDataTypeMapper().getClass();
		Class<?> structureContextType = context.getClass();
		T obj = context.getStructureInstance();

		for (Field f : contextFields) {
			Class<?> fieldType = f.getType();
			if (fieldType.isAssignableFrom(dataTypeMapperType)) {
				ReflectionHelper.assignField(f, obj, context.getDataTypeMapper());
			}
			else if (fieldType.isAssignableFrom(structureContextType)) {
				ReflectionHelper.assignField(f, obj, context);
			}
			else {
				throw new IOException("Unsupported context field: " + f);
			}
		}
	}

	private DataTypeComponent getField(String name) {
		if (!useFieldMappingInfo || name == null || name.isBlank()) {
			return null;
		}
		for (DataTypeComponent dtc : structureDataType.getDefinedComponents()) {
			if (name.equals(dtc.getFieldName())) {
				return dtc;
			}
		}
		return null;
	}

	private void assignField(FieldContext<T> fieldContext, Object value)
			throws IOException {
		Field field = fieldContext.fieldInfo().getField();
		T structureInstance = fieldContext.getStructureInstance();

		ReflectionHelper.assignField(field, structureInstance, value);
	}

	private void readFieldInfo(Class<?> clazz) {
		Class<?> superclass = clazz.getSuperclass();
		if (superclass != null) {
			readFieldInfo(superclass);
		}

		for (Field field : clazz.getDeclaredFields()) {

			FieldMapping fma = field.getAnnotation(FieldMapping.class);
			FieldOutput foa = field.getAnnotation(FieldOutput.class);
			if (fma != null || foa != null) {
				FieldMappingInfo<T> fmi = readFieldMappingInfo(field, fma);
				field.setAccessible(true);
				fields.add(fmi);

				if (foa != null) {
					FieldOutputInfo<T> foi = readFieldOutputInfo(fmi, foa);
					field.setAccessible(true);
					outputFields.add(foi);
				}
				continue;
			}

			ContextField cfa = field.getAnnotation(ContextField.class);
			if (cfa != null) {
				field.setAccessible(true);
				contextFields.add(field);
				if (StructureContext.class.isAssignableFrom(field.getType())) {
					structureContextField = field;
				}
			}
		}
	}

	private FieldMappingInfo<T> readFieldMappingInfo(Field field, FieldMapping fma) {
		String fieldName = fma != null && !fma.fieldName().isBlank()
				? fma.fieldName()
				: field.getName();
		DataTypeComponent dtc = getField(fieldName);
		if (useFieldMappingInfo && dtc == null) {
			throw new IllegalArgumentException("Missing structure field: %s in %s"
					.formatted(fieldName, targetClass.getSimpleName()));
		}

		Signedness signedness = fma != null ? fma.signedness() : Signedness.Unspecified;
		int length = fma != null ? fma.length() : -1;
		FieldMappingInfo<T> fmi = useFieldMappingInfo
				? FieldMappingInfo.createEarlyBinding(field, dtc, signedness, length)
				: FieldMappingInfo.createLateBinding(field, fieldName, signedness, length);

		fmi.setReadFuncClass(fma != null ? fma.readFunc() : FieldReadFunction.class);
		fmi.addMarkupNestedFuncs();
		fmi.addCommentMarkupFuncs();
		fmi.addMarkupReferenceFunc();

		return fmi;
	}

	private FieldOutputInfo<T> readFieldOutputInfo(FieldMappingInfo<T> fmi, FieldOutput foa) {
		FieldOutputInfo<T> foi = new FieldOutputInfo<>(fmi, foa.dataTypeName(),
			foa.isVariableLength(), foa.ordinal(), foa.offset());

		foi.setOutputFuncClass(foa.fieldOutputFunc(), foa.getter());

		return foi;
	}

	private ObjectInstanceCreator<T> findInstanceCreator() {
		Constructor<T> ctor1 = ReflectionHelper.getCtor(targetClass, StructureContext.class);
		if (ctor1 != null) {
			return (context) -> ReflectionHelper.callCtor(ctor1, context);
		}
		Constructor<T> ctor2 = ReflectionHelper.getCtor(targetClass);
		if (ctor2 != null) {
			return (context) -> ReflectionHelper.callCtor(ctor2);
		}
		throw new IllegalArgumentException(
			"Bad instance creator for " + targetClass.getSimpleName());
	}

	private void addPlateCommentMarkupFuncs(PlateComment pca) {
		Method commentGetter =
			ReflectionHelper.getCommentMethod(targetClass, pca.value(), "toString");
		markupFuncs.add((context, session) -> {
			T obj = context.getStructureInstance();
			Object val = ReflectionHelper.callGetter(commentGetter, obj);
			if (val != null) {
				session.appendComment(context, CodeUnit.PLATE_COMMENT, null, val.toString(), "\n");
			}
		});
	}

	private StructureMarkupFunction<T> createMarkupFuncFromGetter(Method markupGetterMethod) {
		return (context, session) -> {
			T obj = context.getStructureInstance();
			Object val = ReflectionHelper.callGetter(markupGetterMethod, obj);
			session.markup(val, false);
		};
	}

	private static int getStructLength(Structure struct) {
		return struct.isZeroLength() ? 0 : struct.getLength();
	}

	//---------------------------------------------------------------------------------------------
	interface ReadFromStructureFunction<T> {
		T readStructure(StructureContext<T> context) throws IOException;
	}

	interface ObjectInstanceCreator<T> {
		T get(StructureContext<T> context) throws IOException;
	}

}
