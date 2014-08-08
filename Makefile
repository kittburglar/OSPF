all: router.class
router.class: router.java
	javac -d . -classpath . router.java
clean:
	rm -f *.class 
