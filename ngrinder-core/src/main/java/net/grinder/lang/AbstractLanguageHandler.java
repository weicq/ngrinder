/* 
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package net.grinder.lang;

import net.grinder.util.AbstractGrinderClassPathProcessor;

/**
 * Scripting Language Handler.
 * 
 * Define common logic for the each scripting language.
 * 
 * @author JunHo Yoon
 * @since 3.2
 */
public abstract class AbstractLanguageHandler {
	private final String extension;
	private final String title;
	private final String codemirrorKey;

	/**
	 * Constructor.
	 * 
	 * @param extension
	 *            file extension
	 * @param title
	 *            title
	 * @param codemirrorKey
	 *            code mirror syntax highlighter key
	 */
	public AbstractLanguageHandler(String extension, String title, String codemirrorKey) {
		this.extension = extension;
		this.title = title;
		this.codemirrorKey = codemirrorKey;
	}

	public String getExtension() {
		return extension;
	}

	public String getTitle() {
		return title;
	}

	public String getCodemirrorKey() {
		return codemirrorKey;
	}

	/**
	 * Get class path processor.
	 * 
	 * @return {@link AbstractGrinderClassPathProcessor} instance.
	 */
	public abstract AbstractGrinderClassPathProcessor getClassPathProcesssor();

	/**
	 * Perform syntax checking on the given script.
	 * 
	 * @param script
	 *            script
	 * @return error message
	 */
	public abstract String checkSyntaxErrors(String script);
}