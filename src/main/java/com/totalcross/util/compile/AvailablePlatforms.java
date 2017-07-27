package com.totalcross.util.compile;

public enum AvailablePlatforms {
	WIN32,
	ANDROID,
	IOS,
	WP8,
	WINCE,
	WINMOBILE,
	LINUX;

	public String toFlag() {
		switch (this) {
		case ANDROID:
		case IOS:
		case LINUX:
		case WIN32:
		case WP8:
			return this.toString().toLowerCase();
		case WINCE:
			return "wince";
		case WINMOBILE:
			return "winmo";
		default:
			break;
		}
		return null;
	}
}
