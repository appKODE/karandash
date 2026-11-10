#!/bin/bash
# Grammar validation script

echo "=== Karandash Grammar Validation ==="
echo ""

# 1. Check JSON validity
echo "1. Validating JSON syntax..."
if python3 -m json.tool syntaxes/karandash.tmLanguage.json > /dev/null 2>&1; then
    echo "   ✅ JSON syntax is valid"
else
    echo "   ❌ JSON syntax is invalid"
    exit 1
fi

# 2. Check schema reference
echo "2. Checking schema reference..."
if grep -q "martinring/tmlanguage" syntaxes/karandash.tmLanguage.json; then
    echo "   ✅ Schema reference present"
else
    echo "   ❌ Schema reference missing"
fi

# 3. Check required fields
echo "3. Checking required fields..."
for field in "name" "scopeName" "patterns" "repository"; do
    if jq -e ".$field" syntaxes/karandash.tmLanguage.json > /dev/null 2>&1; then
        echo "   ✅ Field '$field' present"
    else
        echo "   ❌ Field '$field' missing"
    fi
done

# 4. Check repository patterns
echo "4. Checking repository patterns..."
for pattern in "comments" "keywords" "validators" "properties" "strings" "numbers" "booleans" "regex" "functions" "operators"; do
    if jq -e ".repository.$pattern" syntaxes/karandash.tmLanguage.json > /dev/null 2>&1; then
        echo "   ✅ Pattern '$pattern' present"
    else
        echo "   ❌ Pattern '$pattern' missing"
    fi
done

# 5. Check package.json registration
echo "5. Checking package.json registration..."
if jq -e '.contributes.grammars[] | select(.language == "karandash")' package.json > /dev/null 2>&1; then
    echo "   ✅ Grammar registered in package.json"
else
    echo "   ❌ Grammar not registered in package.json"
fi

# 6. Check test file exists
echo "6. Checking test file..."
if [ -f "test-syntax.kts" ]; then
    echo "   ✅ Test file exists ($(wc -l < test-syntax.kts) lines)"
else
    echo "   ❌ Test file missing"
fi

# 7. Count validators
echo "7. Counting validators..."
VALIDATOR_COUNT=$(jq -r '.repository.validators.patterns[0].match' syntaxes/karandash.tmLanguage.json | grep -o '|' | wc -l)
echo "   ✅ $((VALIDATOR_COUNT + 1)) validators defined"

echo ""
echo "=== Validation Complete ==="
