# Bare Minimum PasteBin

This project provides an extremely small and light PasteBin server that can run almost anywhere Java 8 is installed.  There's only one dependency:  Apache Commons Text, to properly escape HTML.  There's no application server and it doesn't use Spring Boot.

It's an exercise in minimalism.  You might find it quaint.  With only a single JAR as a dependency, we don't need Maven or Gradle.  The build scripts don't need to create a JAR.  How small can we make it while still allowing for maintenance, additional features, and testing?

It may be useful to you if all of the following are true.

* You're headed to the cottage, or somewhere with a good WiFi router but spotty internet (OR) You want to copy and paste text between computers without it going to the internet but you're OK with it being unsecured on the local network.
* You want to be able to copy and paste snippets of text (not Rich Text) between your devices.
* You have a web browser of some kind on all devices you want to copy text from/to, and a shared OS clipboard.  (Sorry, PS4 fans.)
* There's going to be a computer there, or at least something capable of getting and running a Java 1.8 (or later) program.  (JDK 1.8 was released March 2014.)
* You're able to prepare before you head up, or download something small (1 MB) at an opportune time.
* The WiFi is trusted, and you're not copying anything confidential, so you don't need any kind of security or authentication.

## Quick Overview

This utility provides a very simple website that allows you to submit text in one browser and copy it in another (i.e. a pastebin).

If you run the utility with no arguments, it will look for an interface to listen on that starts with "192.168".  The utility will always print the list of discovered interfaces.  If you need it to listen on another interface, provide one argument that matches at most one interface.  (The argument is not a regex.)  If the argument matches more than one interface, all matches are shown.

## Detailed Notes

Using the website shouldn't require any instructions, but the following details may be helpful.  There are three lists of text items:  the pinned list, the active list, and the deleted list.  The pinned list is shown at the top of the page.  There is no limit on the number of pinned items, but the more items you have, the further you will need to scroll in order to find the submit box.

There is a limit of 20 active items.  When you hit the limit, the oldest entry is moved to the deleted list.  Whenever the deleted list is changed, any items that were deleted more than 32 days ago (the longest month plus one day) are gone for good.  In other words, if you come back after a long vacation, you can still recover old deleted items up until the point where a pinned item or active item is deleted.  The created time is not taken into account:  only the deleted date is used to determine if an entry should be permanently deleted.

At present, there is no way to specifically remove individual deleted items forever through the website other than waiting for them to cycle out on their own.  Free yourself from the tyranny of manually managing your history!  Let things cycle out on their own.  Don't use this utility to copy passwords.
