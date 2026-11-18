package com.jiku.shared

/**
 * An image link an organizer may type in (logo, banner): `https://` only and no
 * credentials before the host, or empty to clear it. The site fetches these to
 * draw cards, so an internal or plain-text address is refused at the door.
 */
const val HTTPS_IMAGE_URL = "^(https://[^\\s/@]+(/\\S*)?)?$"
