/*
 * PathUtils.cpp
 *
 *  Created on: 2017-11-22

 */

#include "./PathUtils.hh"

namespace efc {
namespace ezk {

void PathUtils::validatePath(EString path, boolean isSequential) {
	if (isSequential) {
		EString s(path);
		s += "1";
		validatePath(s);
	} else {
		validatePath(path);
	}
}

void PathUtils::validatePath(EString path) {
	if (path.length() == 0) {
		throw EIllegalArgumentException(__FILE__, __LINE__, "Path length must be > 0");
	}
	if (path.charAt(0) != '/') {
		throw EIllegalArgumentException(__FILE__, __LINE__,
					 "Path must start with / character");
	}
	if (path.length() == 1) { // done checking - it's the root
		return;
	}
	if (path.charAt(path.length() - 1) == '/') {
		throw EIllegalArgumentException(__FILE__, __LINE__,
					 "Path must not end with / character");
	}

	EString reason;
	char lastc = '/';
	const char *chars = path.c_str();
	int len = path.length();
	char c;
	for (int i = 1; i < len; lastc = chars[i], i++) {
		c = chars[i];

		if (c == 0) {
			reason = EString("null character not allowed @") + i;
			break;
		} else if (c == '/' && lastc == '/') {
			reason = EString("empty node name specified @") + i;
			break;
		} else if (c == '.' && lastc == '.') {
			if (chars[i-2] == '/' &&
					((i + 1 == len)
							|| chars[i+1] == '/')) {
				reason = EString("relative paths not allowed @") + i;
				break;
			}
		} else if (c == '.') {
			if (chars[i-1] == '/' &&
					((i + 1 == len)
							|| chars[i+1] == '/')) {
				reason = EString("relative paths not allowed @") + i;
				break;
			}
		} /*else if (c > '\u0000' && c < '\u001f'
				|| c > '\u007f' && c < '\u009F'
				|| c > '\ud800' && c < '\uf8ff'
				|| c > '\ufff0' && c < '\uffff') {
			reason = "invalid charater @" + i;
			break;
		}*/
	}

	if (!reason.isEmpty()) {
		throw EIllegalArgumentException(__FILE__, __LINE__,
				EString::formatOf("Invalid path string \"%s\" caused by %s",
						path.c_str(), reason.c_str()).c_str());
	}
}

} /* namespace ezk */
} /* namespace efc */
