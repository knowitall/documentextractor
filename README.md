There are a number of hidden URLs defined in the routes file (`conf/routes`)
that provide methods of getting Precision-Yield curves as well as annotation
data in different formats.

```
/logentry/:i/annotate/:name
/logentry/:i/gold/:name
/logentry/:i/sentences
/logentry/:i/py/:name
/logentry/:i/graph/:name
```

For example, if you extract a document and use the permalink to the extraction page, you will have an id
for that document (Let's say it's 5).  Now you can visit the page `/logentry/5/annotate/michael` and 
annotate that document using the name "michael".  Next, visit `/logentry/5/py/michael` and see
the PY points from Michael's annotations.

This project currently uses Open IE 4 and ReVerb, but other Open IE extractors can be easily added
for annotations.
