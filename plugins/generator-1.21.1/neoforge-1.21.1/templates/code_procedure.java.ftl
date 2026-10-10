<#-- @formatter:off -->
package ${package};

public class ${name}Procedure {
	public static ${data.returnType} execute() {
		<#if data.returnType == "void">${name}.execute();<#else>return ${name}.execute();</#if>
	}
}
<#-- @formatter:on -->
