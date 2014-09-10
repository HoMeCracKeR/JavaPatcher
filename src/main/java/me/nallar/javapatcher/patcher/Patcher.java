package me.nallar.javapatcher.patcher;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.NotFoundException;
import me.nallar.javapatcher.Log;
import me.nallar.javapatcher.mappings.ClassDescription;
import me.nallar.javapatcher.mappings.FieldDescription;
import me.nallar.javapatcher.mappings.Mappings;
import me.nallar.javapatcher.mappings.MethodDescription;
import me.nallar.javapatcher.util.CollectionsUtil;
import me.nallar.javapatcher.util.DomUtil;
import me.nallar.javapatcher.util.SneakyThrow;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

public class Patcher {
	private final ClassPool classPool;
	private final Mappings mappings;
	private static final String debugPatchedOutput = System.getProperty("patcher.debug", "");
	private Object patchClassInstance;
	private final Map<String, PatchMethodDescriptor> patchMethods = new HashMap<String, PatchMethodDescriptor>();
	private final Map<String, PatchGroup> classToPatchGroup = new HashMap<String, PatchGroup>();

	public Patcher(InputStream config, Class<?> patchesClass, ClassPool classPool, Mappings mappings) {
		for (Method method : patchesClass.getDeclaredMethods()) {
			for (Annotation annotation : method.getDeclaredAnnotations()) {
				if (annotation instanceof Patch) {
					PatchMethodDescriptor patchMethodDescriptor = new PatchMethodDescriptor(method, (Patch) annotation);
					patchMethods.put(patchMethodDescriptor.name, patchMethodDescriptor);
				}
			}
		}
		this.classPool = classPool;
		this.mappings = mappings;
		try {
			patchClassInstance = patchesClass.getDeclaredConstructors()[0].newInstance(classPool, mappings);
		} catch (Exception e) {
			Log.severe("Failed to instantiate patch class", e);
		}
		try {
			readPatchesFromXmlDocument(DomUtil.readDocumentFromInputStream(config));
		} catch (Throwable t) {
			throw SneakyThrow.throw_(t);
		}
	}

	private void readPatchesFromXmlDocument(Document document) {
		List<Element> patchGroupElements = DomUtil.elementList(document.getDocumentElement().getChildNodes());
		for (Element patchGroupElement : patchGroupElements) {
			new PatchGroup(patchGroupElement);
		}
	}

	public boolean willTransform(String name) {
		return getPatchGroup(name) != null;
	}

	public synchronized byte[] transform(String name, byte[] originalBytes) {
		PatchGroup patchGroup = getPatchGroup(name);
		if (patchGroup != null) {
			return patchGroup.getClassBytes(name, originalBytes);
		}
		return originalBytes;
	}

	private PatchGroup getPatchGroup(String name) {
		return classToPatchGroup.get(name);
	}

	private static final Splitter idSplitter = Splitter.on("  ").trimResults().omitEmptyStrings();

	private class PatchGroup {
		public final String name;
		public final boolean onDemand;
		public final ClassPool classPool;
		public final Mappings mappings;
		private final Map<String, ClassPatchDescriptor> patches;
		private final Map<String, byte[]> patchedBytes = new HashMap<String, byte[]>();
		private final List<ClassPatchDescriptor> classPatchDescriptors = new ArrayList<ClassPatchDescriptor>();
		private boolean ranPatches = false;

		private PatchGroup(Element element) {
			Map<String, String> attributes = DomUtil.getAttributes(element);
			name = element.getTagName();
			classPool = Patcher.this.classPool;
			mappings = Patcher.this.mappings;
			obfuscateAttributesAndTextContent(element);
			onDemand = attributes.containsKey("onDemand");
			patches = onDemand ? new HashMap<String, ClassPatchDescriptor>() : null;

			for (Element classElement : DomUtil.elementList(element.getChildNodes())) {
				ClassPatchDescriptor classPatchDescriptor;
				try {
					classPatchDescriptor = new ClassPatchDescriptor(classElement);
				} catch (Throwable t) {
					throw new RuntimeException("Failed to create class patch for " + classElement.getAttribute("id"), t);
				}
				PatchGroup other = classToPatchGroup.get(classPatchDescriptor.name);
				if (other != null) {
					Log.severe("Adding class " + classPatchDescriptor.name + " in patch group " + name + " to patch group with different preSrg setting " + other.name);
				}
				(other == null ? this : other).addClassPatchDescriptor(classPatchDescriptor);
			}
		}

		private void addClassPatchDescriptor(ClassPatchDescriptor classPatchDescriptor) {
			classToPatchGroup.put(classPatchDescriptor.name, this);
			classPatchDescriptors.add(classPatchDescriptor);
			if (onDemand && patches.put(classPatchDescriptor.name, classPatchDescriptor) != null) {
				throw new Error("Duplicate class patch for " + classPatchDescriptor.name + ", but onDemand is set.");
			}
		}

		private void obfuscateAttributesAndTextContent(Element root) {
			// TODO - reimplement environments?
			/*
			for (Element classElement : DomUtil.elementList(root.getChildNodes())) {
				String env = classElement.getAttribute("env");
				if (env != null && !env.isEmpty()) {
					if (!env.equals(getEnv())) {
						root.removeChild(classElement);
					}
				}
			}
			*/
			for (Element element : DomUtil.elementList(root.getChildNodes())) {
				if (!DomUtil.elementList(element.getChildNodes()).isEmpty()) {
					obfuscateAttributesAndTextContent(element);
				} else if (element.getTextContent() != null && !element.getTextContent().isEmpty()) {
					element.setTextContent(mappings.obfuscate(element.getTextContent()));
				}
				Map<String, String> attributes = DomUtil.getAttributes(element);
				for (Map.Entry<String, String> attributeEntry : attributes.entrySet()) {
					element.setAttribute(attributeEntry.getKey(), mappings.obfuscate(attributeEntry.getValue()));
				}
			}
			for (Element classElement : DomUtil.elementList(root.getChildNodes())) {
				String id = classElement.getAttribute("id");
				ArrayList<String> list = Lists.newArrayList(idSplitter.split(id));
				if (list.size() > 1) {
					for (String className : list) {
						Element newClassElement = (Element) classElement.cloneNode(true);
						newClassElement.setAttribute("id", className.trim());
						classElement.getParentNode().insertBefore(newClassElement, classElement);
					}
					classElement.getParentNode().removeChild(classElement);
				}
			}
		}

		private void saveByteCode(byte[] bytes, String name) {
			if (!debugPatchedOutput.isEmpty()) {
				name = name.replace('.', '/') + ".class";
				File file = new File(debugPatchedOutput + '/' + name);
				//noinspection ResultOfMethodCallIgnored
				file.getParentFile().mkdirs();
				try {
					Files.write(bytes, file);
				} catch (IOException e) {
					Log.severe("Failed to save patched bytes for " + name, e);
				}
			}
		}

		public byte[] getClassBytes(String name, byte[] originalBytes) {
			byte[] bytes = patchedBytes.get(name);
			if (bytes != null) {
				return bytes;
			}
			if (onDemand) {
				try {
					bytes = patches.get(name).runPatches().toBytecode();
					patchedBytes.put(name, bytes);
					saveByteCode(bytes, name);
					return bytes;
				} catch (Throwable t) {
					Log.severe("Failed to patch " + name + " in patch group " + name + '.', t);
					return originalBytes;
				}
			}
			runPatchesIfNeeded();
			bytes = patchedBytes.get(name);
			if (bytes == null) {
				Log.severe("Got no patched bytes for " + name);
				return originalBytes;
			}
			return bytes;
		}

		private void runPatchesIfNeeded() {
			if (ranPatches) {
				return;
			}
			ranPatches = true;
			Set<CtClass> patchedClasses = new HashSet<CtClass>();
			for (ClassPatchDescriptor classPatchDescriptor : classPatchDescriptors) {
				try {
					try {
						patchedClasses.add(classPatchDescriptor.runPatches());
					} catch (NotFoundException e) {
						if (e.getMessage().contains(classPatchDescriptor.name)) {
							Log.warning("Skipping patch for " + classPatchDescriptor.name + ", not found.");
						} else {
							throw e;
						}
					}
				} catch (Throwable t) {
					Log.severe("Failed to patch " + classPatchDescriptor.name + " in patch group " + name + '.', t);
				}
			}
			for (CtClass ctClass : patchedClasses) {
				String className = ctClass.getName();
				if (!ctClass.isModified()) {
					Log.severe("Failed to get patched bytes for " + className + " as it was never modified in patch group " + name + '.');
					continue;
				}
				try {
					byte[] byteCode = ctClass.toBytecode();
					patchedBytes.put(className, byteCode);
					saveByteCode(byteCode, className);
				} catch (Throwable t) {
					Log.severe("Failed to get patched bytes for " + className + " in patch group " + name + '.', t);
				}
			}
		}

		private class ClassPatchDescriptor {
			private final Map<String, String> attributes;
			public final String name;
			public final List<PatchDescriptor> patches = new ArrayList<PatchDescriptor>();

			private ClassPatchDescriptor(Element element) {
				attributes = DomUtil.getAttributes(element);
				ClassDescription deobfuscatedClass = new ClassDescription(attributes.get("id"));
				ClassDescription obfuscatedClass = mappings.map(deobfuscatedClass);
				name = obfuscatedClass == null ? deobfuscatedClass.name : obfuscatedClass.name;
				for (Element patchElement : DomUtil.elementList(element.getChildNodes())) {
					PatchDescriptor patchDescriptor = new PatchDescriptor(patchElement);
					patches.add(patchDescriptor);
					List<MethodDescription> methodDescriptionList = MethodDescription.fromListString(deobfuscatedClass.name, patchDescriptor.getMethods());
					if (!patchDescriptor.getMethods().isEmpty()) {
						patchDescriptor.set("deobf", methodDescriptionList.get(0).getShortName());
						//noinspection unchecked
						patchDescriptor.setMethods(MethodDescription.toListString((List<MethodDescription>) mappings.map(methodDescriptionList)));
					}
					String field = patchDescriptor.get("field"), prefix = "";
					if (field != null && !field.isEmpty()) {
						if (field.startsWith("this.")) {
							field = field.substring("this.".length());
							prefix = "this.";
						}
						String after = "", type = name;
						if (field.indexOf('.') != -1) {
							after = field.substring(field.indexOf('.'));
							field = field.substring(0, field.indexOf('.'));
							if (!field.isEmpty() && (field.charAt(0) == '$') && prefix.isEmpty()) {
								ArrayList<String> parameterList = new ArrayList<String>();
								for (MethodDescription methodDescriptionOriginal : methodDescriptionList) {
									MethodDescription methodDescription = mappings.rmap(mappings.map(methodDescriptionOriginal));
									methodDescription = methodDescription == null ? methodDescriptionOriginal : methodDescription;
									int i = 0;
									for (String parameter : methodDescription.getParameterList()) {
										if (parameterList.size() <= i) {
											parameterList.add(parameter);
										} else if (!parameterList.get(i).equals(parameter)) {
											parameterList.set(i, null);
										}
										i++;
									}
								}
								int parameterIndex = Integer.valueOf(field.substring(1)) - 1;
								if (parameterIndex >= parameterList.size()) {
									if (!parameterList.isEmpty()) {
										Log.severe("Can not obfuscate parameter field " + patchDescriptor.get("field") + ", index: " + parameterIndex + " but parameter list is: " + Joiner.on(',').join(parameterList));
									}
									break;
								}
								type = parameterList.get(parameterIndex);
								if (type == null) {
									Log.severe("Can not obfuscate parameter field " + patchDescriptor.get("field") + " automatically as this parameter does not have a single type across the methods used in this patch.");
									break;
								}
								prefix = field + '.';
								field = after.substring(1);
								after = "";
							}
						}
						FieldDescription obfuscatedField = mappings.map(new FieldDescription(type, field));
						if (obfuscatedField != null) {
							patchDescriptor.set("field", prefix + obfuscatedField.name + after);
						}
					}
				}
			}

			public CtClass runPatches() throws NotFoundException {
				CtClass ctClass = classPool.get(name);
				for (PatchDescriptor patchDescriptor : patches) {
					PatchMethodDescriptor patchMethodDescriptor = patchMethods.get(patchDescriptor.getPatch());
					Object result = patchMethodDescriptor.run(patchDescriptor, ctClass, patchClassInstance);
					if (result instanceof CtClass) {
						ctClass = (CtClass) result;
					}
				}
				return ctClass;
			}
		}
	}

	private static class PatchDescriptor {
		private final Map<String, String> attributes;
		private String methods;
		private final String patch;

		PatchDescriptor(Element element) {
			attributes = DomUtil.getAttributes(element);
			methods = element.getTextContent().trim();
			patch = element.getTagName();
		}

		public String set(String name, String value) {
			return attributes.put(name, value);
		}

		public String get(String name) {
			return attributes.get(name);
		}

		public Map<String, String> getAttributes() {
			return attributes;
		}

		public String getMethods() {
			return methods;
		}

		public String getPatch() {
			return patch;
		}

		public void setMethods(String methods) {
			this.methods = methods;
		}
	}

	public static class PatchMethodDescriptor {
		public final String name;
		public final List<String> requiredAttributes;
		public final Method patchMethod;
		public final boolean isClassPatch;
		public final boolean emptyConstructor;

		public PatchMethodDescriptor(Method method, Patch patch) {
			String name = patch.name();
			if (Arrays.asList(method.getParameterTypes()).contains(Map.class)) {
				this.requiredAttributes = Lists.newArrayList(Splitter.on(",").trimResults().split(patch.requiredAttributes()));
			} else {
				this.requiredAttributes = null;
			}
			if (name == null || name.isEmpty()) {
				name = method.getName();
			}
			this.name = name;
			emptyConstructor = patch.emptyConstructor();
			isClassPatch = method.getParameterTypes()[0].equals(CtClass.class);
			patchMethod = method;
		}

		public Object run(PatchDescriptor patchDescriptor, CtClass ctClass, Object patchClassInstance) {
			String methods = patchDescriptor.getMethods();
			Map<String, String> attributes = patchDescriptor.getAttributes();
			Map<String, String> attributesClean = new HashMap<String, String>(attributes);
			attributesClean.remove("code");
			Log.fine("Patching " + ctClass.getName() + " with " + this.name + '(' + CollectionsUtil.mapToString(attributesClean) + ')' + (methods.isEmpty() ? "" : " {" + methods + '}'));
			if (requiredAttributes != null && !attributes.keySet().containsAll(requiredAttributes)) {
				Log.severe("Missing required attributes " + requiredAttributes.toString() + " when patching " + ctClass.getName());
				return null;
			}
			if ("^all^".equals(methods)) {
				patchDescriptor.set("silent", "true");
				List<CtBehavior> ctBehaviors = new ArrayList<CtBehavior>();
				Collections.addAll(ctBehaviors, ctClass.getDeclaredMethods());
				Collections.addAll(ctBehaviors, ctClass.getDeclaredConstructors());
				CtBehavior initializer = ctClass.getClassInitializer();
				if (initializer != null) {
					ctBehaviors.add(initializer);
				}
				for (CtBehavior ctBehavior : ctBehaviors) {
					run(ctBehavior, attributes, patchClassInstance);
				}
			} else if (isClassPatch || (!emptyConstructor && methods.isEmpty())) {
				return run(ctClass, attributes, patchClassInstance);
			} else if (methods.isEmpty()) {
				for (CtConstructor ctConstructor : ctClass.getDeclaredConstructors()) {
					run(ctConstructor, attributes, patchClassInstance);
				}
			} else if ("^static^".equals(methods)) {
				CtConstructor ctBehavior = ctClass.getClassInitializer();
				if (ctBehavior == null) {
					Log.severe("No static initializer found patching " + ctClass.getName() + " with " + toString());
				} else {
					run(ctBehavior, attributes, patchClassInstance);
				}
			} else {
				List<MethodDescription> methodDescriptions = MethodDescription.fromListString(ctClass.getName(), methods);
				for (MethodDescription methodDescription : methodDescriptions) {
					CtMethod ctMethod;
					try {
						ctMethod = methodDescription.inClass(ctClass);
					} catch (Throwable t) {
						if (!attributes.containsKey("allowMissing")) {
							Log.warning("", t);
						}
						continue;
					}
					run(ctMethod, attributes, patchClassInstance);
				}
			}
			return null;
		}

		private Object run(CtClass ctClass, Map<String, String> attributes, Object patchClassInstance) {
			try {
				if (requiredAttributes == null) {
					return patchMethod.invoke(patchClassInstance, ctClass);
				} else {
					return patchMethod.invoke(patchClassInstance, ctClass, attributes);
				}
			} catch (Throwable t) {
				if (t instanceof InvocationTargetException) {
					t = t.getCause();
				}
				if (t instanceof CannotCompileException && attributes.containsKey("code")) {
					Log.severe("Code: " + attributes.get("code"));
				}
				Log.severe("Error patching " + ctClass.getName() + " with " + toString(), t);
				return null;
			}
		}

		private Object run(CtBehavior ctBehavior, Map<String, String> attributes, Object patchClassInstance) {
			try {
				if (requiredAttributes == null) {
					return patchMethod.invoke(patchClassInstance, ctBehavior);
				} else {
					return patchMethod.invoke(patchClassInstance, ctBehavior, attributes);
				}
			} catch (Throwable t) {
				if (t instanceof InvocationTargetException) {
					t = t.getCause();
				}
				if (t instanceof CannotCompileException && attributes.containsKey("code")) {
					Log.severe("Code: " + attributes.get("code"));
				}
				Log.severe("Error patching " + ctBehavior.getName() + " in " + ctBehavior.getDeclaringClass().getName() + " with " + toString(), t);
				return null;
			}
		}

		@Override
		public String toString() {
			return name;
		}
	}
}
