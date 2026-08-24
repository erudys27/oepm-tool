class example.calculator.Calculator:

    method public integer Add(input pLeft as integer, input pRight as integer):
        return pLeft + pRight.
    end method.

    /* Real cross-package reference: this fixture depends on
       example.greeter (declared in its own openedge-project.json), not
       just something the consuming test fixture happens to also depend
       on directly - exercises transitive dependency resolution. */
    method public character Describe():
        define variable oGreeter as example.greeter.Greeter no-undo.

        oGreeter = new example.greeter.Greeter().
        return oGreeter:Greet("Calculator").
    end method.

end class.
