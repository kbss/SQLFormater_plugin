/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2008, Red Hat Middleware LLC or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Middleware LLC.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 *
 */
package org.hibernate.engine.jdbc.internal;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * Performs formatting of basic SQL statements (DML + query).
 * 
 * @author Gavin King
 * @author Steve Ebersole
 */
public class BasicFormatterImpl implements Formatter {

	private static final Set<String> BEGIN_CLAUSES = new HashSet<String>();
	private static final Set<String> END_CLAUSES = new HashSet<String>();
	private static final Set<String> LOGICAL = new HashSet<String>();
	private static final Set<String> QUANTIFIERS = new HashSet<String>();
	private static final Set<String> DML = new HashSet<String>();
	private static final Set<String> MISC = new HashSet<String>();
	public static final String WHITESPACE = " \n\r\f\t";
	private boolean clauseToUpperCase;

	static {
		BEGIN_CLAUSES.add("left");
		BEGIN_CLAUSES.add("right");
		BEGIN_CLAUSES.add("inner");
		BEGIN_CLAUSES.add("outer");
		BEGIN_CLAUSES.add("group");
		BEGIN_CLAUSES.add("order");

		END_CLAUSES.add("where");
		END_CLAUSES.add("set");
		END_CLAUSES.add("having");
		END_CLAUSES.add("join");
		END_CLAUSES.add("from");
		END_CLAUSES.add("by");
		END_CLAUSES.add("join");
		END_CLAUSES.add("into");
		END_CLAUSES.add("union");

		LOGICAL.add("and");
		LOGICAL.add("or");
		LOGICAL.add("when");
		LOGICAL.add("else");
		LOGICAL.add("end");

		QUANTIFIERS.add("in");
		QUANTIFIERS.add("all");
		QUANTIFIERS.add("exists");
		QUANTIFIERS.add("some");
		QUANTIFIERS.add("any");

		DML.add("insert");
		DML.add("update");
		DML.add("delete");

		MISC.add("select");
		MISC.add("on");
	}

	private static final String indentString = "    ";
	private static final String initial = "\n    ";

	public BasicFormatterImpl(boolean clauseToUpperCase) {
		this.clauseToUpperCase = clauseToUpperCase;
	}

	public String format(String source) {
		return new FormatProcess(source, clauseToUpperCase).perform();
	}

	private static class FormatProcess {
		boolean beginLine = true;
		boolean afterBeginBeforeEnd = false;
		boolean afterByOrSetOrFromOrSelect = false;
		boolean afterOn = false;
		boolean afterBetween = false;
		boolean afterInsert = false;
		private boolean clauseToUpperCase;
		int inFunction = 0;
		int parensSinceSelect = 0;
		private LinkedList<Integer> parenCounts = new LinkedList<Integer>();
		private LinkedList<Boolean> afterByOrFromOrSelects = new LinkedList<Boolean>();

		int indent = 1;

		StringBuffer result = new StringBuffer();
		StringTokenizer tokens;
		String lastToken;
		String token;
		String lcToken;

		public FormatProcess(String sql, boolean clauseToUpperCase) {
			tokens = new StringTokenizer(sql, "()+*/-=<>'`\"[]," + WHITESPACE,
					true);
			this.clauseToUpperCase = clauseToUpperCase;
		}

		public String perform() {
			result.append(initial);
			String previousToken = "";
			while (tokens.hasMoreTokens()) {
				token = tokens.nextToken();
				lcToken = token.toLowerCase();
				if ("'".equals(token)) {
					String t;
					do {
						t = tokens.nextToken();
						token += t;
					} while (!"'".equals(t) && tokens.hasMoreTokens()); // cannot
																		// handle
																		// single
																		// quotes
				} else if ("\"".equals(token)) {
					String t;
					do {
						t = tokens.nextToken();
						token += t;
					} while (!"\"".equals(t));
				}
				if (afterByOrSetOrFromOrSelect && ",".equals(token)) {
					commaAfterByOrFromOrSelect();
				} else if (afterOn && ",".equals(token)) {
					commaAfterOn();
				}

				else if ("(".equals(token)) {
					openParen();
				} else if (")".equals(token)) {
					closeParen();
				}

				else if (BEGIN_CLAUSES.contains(lcToken)) {
					beginNewClause();
				}

				else if (END_CLAUSES.contains(lcToken)) {
					endNewClause();
				}

				else if ("select".equals(lcToken)) {
					select();
				}

				else if (DML.contains(lcToken)) {
					updateOrInsertOrDelete();
				}

				else if ("values".equals(lcToken)) {
					values();
				}

				else if ("on".equals(lcToken)) {
					on();
				}

				else if (afterBetween && lcToken.equals("and")) {
					misc();
					afterBetween = false;
				}

				else if (LOGICAL.contains(lcToken)) {
					logical();
				}

				else if (isWhitespace(token)) {
					if (!" ".equals(previousToken)) {
						white();
					}
				}

				else {
					misc();
				}

				if (!isWhitespace(token)) {
					lastToken = lcToken;
				}
				previousToken = token;
			}
			return result.toString();
		}

		private void commaAfterOn() {
			out();
			indent--;
			newline();
			afterOn = false;
			afterByOrSetOrFromOrSelect = true;
		}

		private void commaAfterByOrFromOrSelect() {
			out();
			newline();
		}

		private void logical() {
			if ("end".equals(lcToken)) {
				indent--;
			}
			newline();
			out(clauseToUpperCase);
			beginLine = false;
		}

		private void on() {
			indent++;
			afterOn = true;
			newline();
			out(clauseToUpperCase);
			beginLine = false;
		}

		private void misc() {
			out();
			if ("between".equals(lcToken)) {
				afterBetween = true;
			}
			if (afterInsert) {
				newline();
				afterInsert = false;
			} else {
				beginLine = false;
				if ("case".equals(lcToken)) {
					indent++;
				}
			}
		}

		private void white() {
			if (!beginLine) {
				result.append(" ");
			}
		}

		private void updateOrInsertOrDelete() {
			out(clauseToUpperCase);
			indent++;
			beginLine = false;
			if ("update".equals(lcToken)) {
				newline();
			}
			if ("insert".equals(lcToken)) {
				afterInsert = true;
			}
		}

		private void select() {
			out(clauseToUpperCase);
			indent++;
			newline();
			parenCounts.addLast(Integer.valueOf(parensSinceSelect));
			afterByOrFromOrSelects.addLast(Boolean
					.valueOf(afterByOrSetOrFromOrSelect));
			parensSinceSelect = 0;
			afterByOrSetOrFromOrSelect = true;
		}

		private void out() {
			result.append(token);
		}

		private void out(boolean upperCase) {
			if (upperCase) {
				result.append(token.toUpperCase());
			} else {
				result.append(token);
			}

		}

		private void endNewClause() {
			if (!afterBeginBeforeEnd) {
				indent--;
				if (afterOn) {
					indent--;
					afterOn = false;
				}
				newline();
			}
			out(clauseToUpperCase);
			if (!"union".equals(lcToken)) {
				indent++;
			}
			newline();
			afterBeginBeforeEnd = false;
			afterByOrSetOrFromOrSelect = "by".equals(lcToken)
					|| "set".equals(lcToken) || "from".equals(lcToken);
		}

		private void beginNewClause() {
			if (!afterBeginBeforeEnd) {
				if (afterOn) {
					indent--;
					afterOn = false;
				}
				indent--;
				newline();
			}
			out(clauseToUpperCase);
			beginLine = false;
			afterBeginBeforeEnd = true;
		}

		private void values() {
			indent--;
			newline();
			out();
			indent++;
			newline();
		}

		private void closeParen() {
			parensSinceSelect--;
			if (parensSinceSelect < 0) {
				indent--;
				parensSinceSelect = parenCounts.removeLast().intValue();
				afterByOrSetOrFromOrSelect = afterByOrFromOrSelects
						.removeLast().booleanValue();
			}
			if (inFunction > 0) {
				inFunction--;
				out();
			} else {
				if (!afterByOrSetOrFromOrSelect) {
					indent--;
					newline();
				}
				out();
			}
			beginLine = false;
		}

		private void openParen() {
			if (isFunctionName(lastToken) || inFunction > 0) {
				inFunction++;
			}
			beginLine = false;
			if (inFunction > 0) {
				out(clauseToUpperCase);
			} else {
				out(clauseToUpperCase);
				if (!afterByOrSetOrFromOrSelect) {
					indent++;
					newline();
					beginLine = true;
				}
			}
			parensSinceSelect++;
		}

		private static boolean isFunctionName(String tok) {
			final char begin = tok.charAt(0);
			final boolean isIdentifier = Character.isJavaIdentifierStart(begin)
					|| '"' == begin;
			return isIdentifier && !LOGICAL.contains(tok)
					&& !END_CLAUSES.contains(tok) && !QUANTIFIERS.contains(tok)
					&& !DML.contains(tok) && !MISC.contains(tok);
		}

		private static boolean isWhitespace(String token) {
			return WHITESPACE.indexOf(token) >= 0;
		}

		private void newline() {
			result.append("\n");
			for (int i = 0; i < indent; i++) {
				result.append(indentString);
			}
			beginLine = true;
		}
	}

}