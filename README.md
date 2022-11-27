# Bare Minimum PasteBin

This project is intended for very specific circumstances.

* You're headed to the cottage, or somewhere with a good WiFi router but spotty internet.
* You want to be able to copy and paste snippets of text (not Rich Text) between your devices.
* You have a web browser of some kind on all devices you want to copy text from/to, and a shared clipboard.  (Sorry, PS4 fans.)
* There's going to be a computer there, or at least something capable of getting and running a Java 1.8 (or later) program.  (JDK 1.8 was released March 2014.)
* You're able to prepare before you head up, or download something small (1 MB) at an opportune time.
* The WiFi is trusted, and you're not copying anything confidential, so you don't need any kind of security or authentication.

If all of those are true, then this utility might be useful to you.

## Quick Overview

This utility provides a very simple website that allows you to submit text in one browser and copy it in another (i.e. a pastebin).  Using the website shouldn't require any instructions.  To run it, you need to provide a filename where the snippets of text will be kept between runs and enough information for it to determine what interface it should listen on.

There are three lists of text items:  the pinned list, the active list, and the deleted list.  The pinned list is shown at the top of the page.  There is no limit on the number of pinned items, but the more items you have, the further you will need to scroll  in order to find the submit box.

There is a limit of 20 active items.  When you hit the limit, the oldest entry is moved to the deleted list.  Whenever the deleted list is changed, any items that were deleted more than 32 days ago (the longest month plus one day) are gone for good.  In other words, if you come back after a long vacation, you can still recover old deleted items up until the point where a pinned item or active item is deleted.

At present, there is no way to specifically remove individual deleted items forever through the website other than waiting for them to cycle out on their own.  Free yourself from the tyranny of manually managing your history!  Let things cycle out on their own.
